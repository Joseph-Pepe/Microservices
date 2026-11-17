package com.example.demo;

import com.example.demo.config.AdaptiveConcurrencyFilter;
import com.example.demo.config.DistributedRateLimiterFilter;
import com.example.demo.security.InternalAuthTokenService;
import com.example.demo.security.InternalAuthTokenService.InternalAuthTokenException;
import com.example.demo.security.InternalAuthTokenService.InternalClaims;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("local") // 1. CRITICAL: Boots local profile to enable LocalSecurityMockConfig!
class GatewaySecurityAndRateLimitTests {

    private static final String TEST_SECRET = "dev-only-internal-auth-hmac-secret-key-change-in-prod-32bytes!";
    private static final InternalAuthTokenService DOWNSTREAM_VERIFIER =
            new InternalAuthTokenService(TEST_SECRET, "api-gateway", 30, 5, null);

    private static HttpServer mockBackendServer;
    private static int mockBackendPort;
    private static final AtomicInteger postNoRetryHits = new AtomicInteger(0);
    private static final AtomicInteger getRetryHits = new AtomicInteger(0);
    private static final AtomicInteger slowAiHits = new AtomicInteger(0);
    private static final AtomicReference<String> lastObservedLegacyHeader = new AtomicReference<>();
    private static final AtomicReference<String> lastObservedInternalAuthHeader = new AtomicReference<>();
    private static final AtomicReference<InternalClaims> lastVerifiedClaims = new AtomicReference<>();

    private static volatile CountDownLatch slowSlotsOccupiedLatch = new CountDownLatch(0);
    private static volatile CountDownLatch releaseSlowRequestsLatch = new CountDownLatch(0);

    @BeforeAll
    static void startMockBackend() throws IOException {
        mockBackendServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockBackendPort = mockBackendServer.getAddress().getPort();

        mockBackendServer.createContext("/actuator/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockBackendServer.createContext("/api/vectors/ping", exchange -> {
            if (!verifyDownstreamInternalAuth(exchange)) {
                return;
            }
            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockBackendServer.createContext("/api/vectors/calculateMagnitude", exchange -> {
            if (!verifyDownstreamInternalAuth(exchange)) {
                return;
            }
            byte[] body = "37.416573867739416".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockBackendServer.createContext("/api/vectors/slow-ai", exchange -> {
            if (!verifyDownstreamInternalAuth(exchange)) {
                return;
            }
            slowAiHits.incrementAndGet();
            slowSlotsOccupiedLatch.countDown();
            try {
                releaseSlowRequestsLatch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"status\":\"completed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockBackendServer.createContext("/api/vectors/no-retry-post", exchange -> {
            if (!verifyDownstreamInternalAuth(exchange)) {
                return;
            }
            postNoRetryHits.incrementAndGet();
            exchange.sendResponseHeaders(504, -1);
            exchange.close();
        });

        mockBackendServer.createContext("/api/vectors/retry-get", exchange -> {
            if (!verifyDownstreamInternalAuth(exchange)) {
                return;
            }
            getRetryHits.incrementAndGet();
            exchange.sendResponseHeaders(504, -1);
            exchange.close();
        });

        // Immediately close connection without sending HTTP headers -> IOException -> CircuitBreaker fallback
        mockBackendServer.createContext("/api/vectors/trigger-fallback", exchange -> exchange.close());
        mockBackendServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        mockBackendServer.start();
    }

    private static boolean verifyDownstreamInternalAuth(HttpExchange exchange) throws IOException {
        String legacy = exchange.getRequestHeaders().getFirst(InternalAuthTokenService.LEGACY_HEADER_NAME);
        String internalAuth = exchange.getRequestHeaders().getFirst(InternalAuthTokenService.HEADER_NAME);
        lastObservedLegacyHeader.set(legacy);
        lastObservedInternalAuthHeader.set(internalAuth);

        if (internalAuth == null || internalAuth.isBlank()) {
            byte[] body = "{\"error\":\"Forbidden\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return false;
        }
        try {
            InternalClaims claims = DOWNSTREAM_VERIFIER.verifyToken(internalAuth);
            lastVerifiedClaims.set(claims);
            return true;
        } catch (InternalAuthTokenException e) {
            byte[] body = "{\"error\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return false;
        }
    }

    @AfterAll
    static void stopMockBackend() {
        if (mockBackendServer != null) {
            mockBackendServer.stop(0);
        }
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        String mockUri = "http://127.0.0.1:" + mockBackendPort;
        registry.add("spring.cloud.discovery.client.simple.instances.vector-service[0].uri", () -> mockUri);
        registry.add("spring.cloud.loadbalancer.health-check.initial-delay", () -> "0s");
        registry.add("spring.cloud.loadbalancer.health-check.interval", () -> "5s");
    }

    @Autowired
    private WebTestClient webClient;

    @MockitoSpyBean
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DistributedRateLimiterFilter rateLimiterFilter;

    @Autowired
    private AdaptiveConcurrencyFilter adaptiveConcurrencyFilter;

    @Autowired
    private InternalAuthTokenService internalAuthTokenService;

    @BeforeEach
    void resetState() {
        // 1. Clear any Mockito outage stubs from previous tests
        Mockito.reset(redisTemplate);
        rateLimiterFilter.resetForTesting();
        adaptiveConcurrencyFilter.resetForTesting();
        postNoRetryHits.set(0);
        getRetryHits.set(0);
        slowAiHits.set(0);
        lastObservedLegacyHeader.set(null);
        lastObservedInternalAuthHeader.set(null);
        lastVerifiedClaims.set(null);
        slowSlotsOccupiedLatch = new CountDownLatch(0);
        releaseSlowRequestsLatch = new CountDownLatch(0);

        try {
            // 2. Ensure every test run starts with a clean slate
            redisTemplate.delete(List.of(
                    "rate_limit:{user:local-admin-tester}",
                    "rate_limit:{user:outage-tester}",
                    "rate_limit:{user:concurrent-burst-tester}",
                    "rate_limit:{user:half-open-tester}",
                    "rate_limit:{user:post-no-retry-tester}",
                    "rate_limit:{user:get-retry-tester}",
                    "rate_limit:{user:fallback-uri-tester}",
                    "rate_limit:{user:spoof-tester}",
                    "rate_limit:{user:concurrency-shed-1}",
                    "rate_limit:{user:concurrency-shed-2}",
                    "rate_limit:{user:concurrency-shed-3}",
                    "rate_limit:{ip:127.0.0.1}",
                    "rate_limit:{ip:0:0:0:0:0:0:0:1}"
            ));
        } catch (Exception ignored) {
            // Safe when running test suite without a live Valkey/Redis instance
        }
    }

    @Test
    void testHackerAttempt_shouldReturn401Unauthorized() {
        // No Authorization header sent -> should block instantly at the perimeter
        webClient.post().uri("/api/vectors/calculateMagnitude")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void testUnauthenticatedRequest_withForgedInternalAuthHeaders_shouldReturn401Unauthorized() {
        webClient.post().uri("/api/vectors/calculateMagnitude")
                .header(InternalAuthTokenService.LEGACY_HEADER_NAME, "true")
                .header(InternalAuthTokenService.HEADER_NAME, "forged.jwt.token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSpoofedInternalHeaders_shouldBeStrippedAndReplacedWithSignedJwt() {
        doReturn(9L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any());

        String spoofedForgedToken = "spoofed.forged.jwt";
        webClient.post().uri("/api/vectors/calculateMagnitude")
                .headers(headers -> headers.setBearerAuth("user.spoof-tester"))
                .header(InternalAuthTokenService.LEGACY_HEADER_NAME, "true")
                .header(InternalAuthTokenService.HEADER_NAME, spoofedForgedToken)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Concurrency-Limit")
                .expectHeader().exists("X-Concurrency-InFlight");

        // Gateway MUST strip legacy X-Gateway-Validated and client-supplied X-Internal-Auth
        assertThat(lastObservedLegacyHeader.get()).isNull();
        assertThat(lastObservedInternalAuthHeader.get())
                .isNotNull()
                .isNotEqualTo(spoofedForgedToken);

        InternalClaims claims = lastVerifiedClaims.get();
        assertThat(claims).isNotNull();
        assertThat(claims.subject()).isEqualTo("spoof-tester");
        assertThat(claims.issuer()).isEqualTo("api-gateway");
        assertThat(claims.jti()).isNotBlank();
        assertThat(claims.roles()).contains("ROLE_ADMIN", "ROLE_USER");
        assertThat(claims.expiresAt() - claims.issuedAt()).isEqualTo(30L);
    }

    @Test
    void testInternalAuthTokenVerification_shouldRejectForgedAndExpiredTokens() throws Exception {
        long now = Instant.now().getEpochSecond();

        // 1. Wrong signing secret (forged token)
        String forgedSecretToken = mintCustomJwt(
                Map.of("alg", "HS256", "typ", "JWT"),
                Map.of("sub", "alice", "roles", List.of("ROLE_USER"), "jti", UUID.randomUUID().toString(),
                        "iss", "api-gateway", "iat", now, "exp", now + 30),
                "attacker-wrong-secret-key-at-least-32-bytes!"
        );
        assertThatThrownBy(() -> internalAuthTokenService.verifyToken(forgedSecretToken))
                .isInstanceOf(InternalAuthTokenException.class)
                .hasMessageContaining("signature");

        // 2. Expired token (exp in the past beyond clock skew)
        String expiredToken = mintCustomJwt(
                Map.of("alg", "HS256", "typ", "JWT"),
                Map.of("sub", "alice", "roles", List.of("ROLE_USER"), "jti", UUID.randomUUID().toString(),
                        "iss", "api-gateway", "iat", now - 60, "exp", now - 30),
                TEST_SECRET
        );
        assertThatThrownBy(() -> internalAuthTokenService.verifyToken(expiredToken))
                .isInstanceOf(InternalAuthTokenException.class)
                .hasMessageContaining("expired");

        // 3. alg=none downgrade attack
        String algNoneToken = mintCustomJwt(
                Map.of("alg", "none", "typ", "JWT"),
                Map.of("sub", "alice", "roles", List.of("ROLE_ADMIN"), "jti", UUID.randomUUID().toString(),
                        "iss", "api-gateway", "iat", now, "exp", now + 30),
                TEST_SECRET
        );
        assertThatThrownBy(() -> internalAuthTokenService.verifyToken(algNoneToken))
                .isInstanceOf(InternalAuthTokenException.class)
                .hasMessageContaining("alg");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAdaptiveConcurrencyShedding_shouldReturnRfc9457_503WhenConcurrencyExceeded() throws Exception {
        doReturn(9L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any());

        // Restrict rest partition concurrency ceiling to 2 slots to test saturation deterministically
        adaptiveConcurrencyFilter.getRestLimiter().overrideLimitForTesting(2);
        slowSlotsOccupiedLatch = new CountDownLatch(2);
        releaseSlowRequestsLatch = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var req1 = executor.submit(() ->
                    webClient.post().uri("/api/vectors/slow-ai")
                            .headers(headers -> headers.setBearerAuth("user.concurrency-shed-1"))
                            .exchange()
                            .expectStatus().isOk()
            );
            var req2 = executor.submit(() ->
                    webClient.post().uri("/api/vectors/slow-ai")
                            .headers(headers -> headers.setBearerAuth("user.concurrency-shed-2"))
                            .exchange()
                            .expectStatus().isOk()
            );

            // Wait until both concurrency slots are actively blocked downstream
            assertThat(slowSlotsOccupiedLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // 3rd request from a distinct user must be shed immediately by AdaptiveConcurrencyFilter (0 backend hits)
            webClient.post().uri("/api/vectors/slow-ai")
                    .headers(headers -> headers.setBearerAuth("user.concurrency-shed-3"))
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectHeader().valueEquals("Retry-After", "2")
                    .expectHeader().valueEquals("X-Concurrency-Limit", "2")
                    .expectHeader().valueEquals("X-Concurrency-InFlight", "2")
                    .expectHeader().contentTypeCompatibleWith(Objects.requireNonNull(MediaType.APPLICATION_PROBLEM_JSON))
                    .expectBody()
                    .jsonPath("$.title").isEqualTo("Service Unavailable")
                    .jsonPath("$.status").isEqualTo(503)
                    .jsonPath("$.instance").isEqualTo("/api/vectors/slow-ai")
                    .jsonPath("$.concurrencyLimit").isEqualTo(2)
                    .jsonPath("$.inFlight").isEqualTo(2)
                    .jsonPath("$.retryAfterSeconds").isEqualTo(2);

            assertThat(slowAiHits.get()).isEqualTo(2);

            // Release the 2 held backend requests and verify slots drain cleanly
            releaseSlowRequestsLatch.countDown();
            req1.get(5, TimeUnit.SECONDS);
            req2.get(5, TimeUnit.SECONDS);
        } finally {
            releaseSlowRequestsLatch.countDown();
        }

        // Subsequent request succeeds once in-flight slots drain back to 0
        assertThat(adaptiveConcurrencyFilter.getRestLimiter().getInFlight()).isZero();
        webClient.post().uri("/api/vectors/slow-ai")
                .headers(headers -> headers.setBearerAuth("user.concurrency-shed-3"))
                .exchange()
                .expectStatus().isOk();
        assertThat(slowAiHits.get()).isEqualTo(3);
    }

    @Test
    void testSpammingServer_shouldTriggerRateLimiter429() {
        var patientClient = webClient.mutate()
                .responseTimeout(Objects.requireNonNull(Duration.ofSeconds(10)))
                .build();

        // 3. Rapidly fire 10 valid requests WITH bearer tokens using modern Java IntStream
        IntStream.range(0, 10).forEach(i ->
                patientClient.post().uri("/api/vectors/calculateMagnitude")
                        .headers(headers -> headers.setBearerAuth("mock-test-token-string"))
                        .exchange()
                        .expectStatus().isOk()
        );

        // 4. An 11th request must be blocked by the atomic Valkey/Redis Lua script.
        // The Lua script has 10 entries for "local-admin-tester" in Redis, so it will block this one.
        patientClient.post().uri("/api/vectors/calculateMagnitude")
                .headers(headers -> headers.setBearerAuth("mock-test-token-string"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("X-RateLimit-Limit", "10")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectHeader().value("Retry-After", value -> {
                    int retrySeconds = Integer.parseInt(value);
                    assertThat(retrySeconds).isBetween(1, 30);
                })
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("Rate limit exceeded!"));
    }

    @Test
    void testPing_shouldEnforceIpRateLimit429() {
        var patientClient = webClient.mutate()
                .responseTimeout(Objects.requireNonNull(Duration.ofSeconds(10)))
                .build();

        // /api/vectors/ping is unauthenticated (permitAll) and must be rate-limited by client IP
        IntStream.range(0, 10).forEach(i ->
                patientClient.get().uri("/api/vectors/ping")
                        .exchange()
                        .expectStatus().isOk()
        );

        assertThat(lastVerifiedClaims.get()).isNotNull();
        assertThat(lastVerifiedClaims.get().subject()).isEqualTo("anonymous");
        assertThat(lastVerifiedClaims.get().roles()).isEmpty();

        patientClient.get().uri("/api/vectors/ping")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("X-RateLimit-Limit", "10")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectHeader().exists("Retry-After");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testValkeyOutage_shouldTripCircuitAndEnforceLocalFallback429() {
        var patientClient = webClient.mutate()
                .responseTimeout(Objects.requireNonNull(Duration.ofSeconds(10)))
                .build();

        // 1. Simulate a complete Valkey/Redis cluster outage
        doThrow(new RedisConnectionFailureException("Simulated Valkey cluster outage"))
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(), any());

        // 2. Fire 10 requests as "user:outage-tester" (supported by LocalSecurityMockConfig:
        //    - Requests 1-5 fail on Redis, trip the Circuit Breaker OPEN, and fall back to local JVM memory.
        //    - Requests 6-10 short-circuit Redis completely (0 network calls) and use local JVM memory.
        IntStream.range(0, 10).forEach(i ->
                patientClient.post().uri("/api/vectors/calculateMagnitude")
                        .headers(headers -> headers.setBearerAuth("user.outage-tester"))
                        .exchange()
                        .expectStatus().isOk()
        );

        // 3. The 11th request must STILL be blocked (HTTP 429) by the Bounded Local Fallback!
        patientClient.post().uri("/api/vectors/calculateMagnitude")
                .headers(headers -> headers.setBearerAuth("user.outage-tester"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("X-RateLimit-Limit", "10")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectHeader().exists("Retry-After");

        // 4. PROVE CIRCUIT BREAKER TRIPPED: Out of 11 HTTP requests, Redis was only called 5 times!
        verify(redisTemplate, times(5))
                .execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testLocalFallback_shouldPreventConcurrentToctouBypass() throws Exception {
        doThrow(new RedisConnectionFailureException("Simulated Valkey cluster outage"))
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(), any());

        int totalThreads = 30;
        var readyLatch = new CountDownLatch(totalThreads);
        var startGate = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(totalThreads);
        var allowedCount = new AtomicInteger(0);
        var blockedCount = new AtomicInteger(0);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < totalThreads; i++) {
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startGate.await(5, TimeUnit.SECONDS);
                        var result = webClient.post().uri("/api/vectors/calculateMagnitude")
                                .headers(headers -> headers.setBearerAuth("user.concurrent-burst-tester"))
                                .exchange()
                                .returnResult(String.class);

                        if (result.getStatus().equals(HttpStatus.OK)) {
                            allowedCount.incrementAndGet();
                        } else if (result.getStatus().equals(HttpStatus.TOO_MANY_REQUESTS)) {
                            blockedCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startGate.countDown();
            assertThat(doneLatch.await(15, TimeUnit.SECONDS)).isTrue();
        }

        // Atomic ConcurrentHashMap.compute GCRA must strictly allow 10 and block 20 with zero TOCTOU leaks
        assertThat(allowedCount.get()).isEqualTo(10);
        assertThat(blockedCount.get()).isEqualTo(20);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testHalfOpen_shouldProbeValkeyOnceAfterCooldownAndRecover() throws Exception {
        doThrow(new RedisConnectionFailureException("Simulated Valkey cluster outage"))
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(), any());

        // 1. 5 failed Valkey calls trip circuit to OPEN
        IntStream.range(0, 5).forEach(i ->
                webClient.post().uri("/api/vectors/calculateMagnitude")
                        .headers(headers -> headers.setBearerAuth("user.half-open-tester"))
                        .exchange()
                        .expectStatus().isOk()
        );
        verify(redisTemplate, times(5)).execute(any(RedisScript.class), anyList(), any(), any());

        // 2. While OPEN, requests short-circuit Valkey completely
        IntStream.range(0, 2).forEach(i ->
                webClient.post().uri("/api/vectors/calculateMagnitude")
                        .headers(headers -> headers.setBearerAuth("user.half-open-tester"))
                        .exchange()
                        .expectStatus().isOk()
        );
        verify(redisTemplate, times(5)).execute(any(RedisScript.class), anyList(), any(), any());

        // 3. Simulate cooldown elapsed and Valkey recovering with a slow probe
        ((AtomicLong) Objects.requireNonNull(
                ReflectionTestUtils.getField(rateLimiterFilter, "circuitOpenUntilMillis")))
                .set(System.currentTimeMillis() - 1L);

        var probeEntered = new CountDownLatch(1);
        var releaseProbe = new CountDownLatch(1);
        doAnswer(invocation -> {
            probeEntered.countDown();
            releaseProbe.await(5, TimeUnit.SECONDS);
            return 2L;
        }).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any());

        // 4. Fire 2 concurrent requests at cooldown expiration: only 1 probe hits Valkey (HALF_OPEN)
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstCallFuture = executor.submit(() ->
                    webClient.post().uri("/api/vectors/calculateMagnitude")
                            .headers(headers -> headers.setBearerAuth("user.half-open-tester"))
                            .exchange()
                            .expectStatus().isOk()
            );

            assertThat(probeEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // Second concurrent request while probe is in-flight must NOT stampede Valkey
            webClient.post().uri("/api/vectors/calculateMagnitude")
                    .headers(headers -> headers.setBearerAuth("user.half-open-tester"))
                    .exchange()
                    .expectStatus().isOk();

            verify(redisTemplate, times(6)).execute(any(RedisScript.class), anyList(), any(), any());

            releaseProbe.countDown();
            firstCallFuture.get(5, TimeUnit.SECONDS);
        }

        // 5. After single HALF_OPEN probe succeeds, circuit is CLOSED again and next request uses Valkey
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any());
        webClient.post().uri("/api/vectors/calculateMagnitude")
                .headers(headers -> headers.setBearerAuth("user.half-open-tester"))
                .exchange()
                .expectStatus().isOk();

        verify(redisTemplate, times(7)).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPostRequest_shouldNotRetryOn504() {
        doReturn(9L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any());

        webClient.post().uri("/api/vectors/no-retry-post")
                .headers(headers -> headers.setBearerAuth("user.post-no-retry-tester"))
                .exchange();

        // POST is non-idempotent: backend must be hit exactly once (zero retries)
        assertThat(postNoRetryHits.get()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetRequest_shouldRetryOn504() {
        var patientClient = webClient.mutate()
                .responseTimeout(Objects.requireNonNull(Duration.ofSeconds(10)))
                .build();

        doReturn(9L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any());

        patientClient.get().uri("/api/vectors/retry-get")
                .headers(headers -> headers.setBearerAuth("user.get-retry-tester"))
                .exchange();

        // GET is idempotent with retries=2: 1 initial attempt + 2 retries = 3 backend hits
        assertThat(getRetryHits.get()).isEqualTo(3);
    }

    @Test
    void testCircuitBreakerFallback_shouldReturnRfc9457ProblemDetail503() {
        // 5. Verify the Resilience4j fallback endpoint returns a valid RFC 9457 ProblemDetail contract
        webClient.get().uri("/fallback")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals("Retry-After", "20")
                .expectHeader().contentTypeCompatibleWith(Objects.requireNonNull(MediaType.APPLICATION_PROBLEM_JSON))
                .expectBody()
                .jsonPath("$.title").isEqualTo("Service Unavailable")
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.detail").isEqualTo("The AI generation service is experiencing heavy traffic and is temporarily unavailable. Please retry shortly.")
                .jsonPath("$.instance").isEqualTo("/fallback")
                .jsonPath("$.retryAfterSeconds").isEqualTo(20)
                .jsonPath("$.timestamp").exists();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testCircuitBreakerFallback_shouldPreserveOriginalForwardUri() {
        doReturn(9L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any());

        webClient.post().uri("/api/vectors/trigger-fallback")
                .headers(headers -> headers.setBearerAuth("user.fallback-uri-tester"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals("Retry-After", "20")
                .expectHeader().contentTypeCompatibleWith(Objects.requireNonNull(MediaType.APPLICATION_PROBLEM_JSON))
                .expectBody()
                .jsonPath("$.instance").isEqualTo("/api/vectors/trigger-fallback");
    }

    private static String mintCustomJwt(Map<String, Object> header, Map<String, Object> payload, String secret) throws Exception {
        var mapper = JsonMapper.builder().build();
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String h = encoder.encodeToString(mapper.writeValueAsString(header).getBytes(StandardCharsets.UTF_8));
        String p = encoder.encodeToString(mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
        String signingInput = h + "." + p;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = encoder.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        return signingInput + "." + sig;
    }
}

// package com.example.demo; 

// import org.junit.jupiter.api.Test;
// import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.boot.test.context.SpringBootTest;
// import org.springframework.http.HttpStatus;
// import org.springframework.test.web.reactive.server.WebTestClient;
// import java.time.Duration;

// @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// class GatewaySecurityAndRateLimitTests {

//     // @Autowired
//     // private WebTestClient webClient; 

//     // @Test
//     // void testHackerAttempt_shouldReturn401Unauthorized() {
//     //     webClient.post().uri("/api/vectors/calculateMagnitude")
//     //             .exchange() 
//     //             .expectStatus().isUnauthorized(); 
//     // }

//     // @Test
//     // void testSpammingServer_shouldTriggerRateLimiter429() {
//     //     WebTestClient patientClient = webClient.mutate()
//     //             .responseTimeout(java.util.Objects.requireNonNull(Duration.ofSeconds(10)))
//     //             .build();

//     //     // 1. We use a loop to rapidly fire 10 valid requests.
//     //     // The bouncer's sliding window will let all 10 of these through.
//     //     for (int i = 0; i < 10; i++) {
//     //         patientClient.post().uri("/api/vectors/calculateMagnitude")
//     //                 .headers(headers -> headers.setBasicAuth("admin", "vector-secret-123"))
//     //                 .exchange(); 
//     //     }

//     //     // 2. The Trap: Fire the 11th request instantly.
//     //     // The queue now has 10 timestamps from the last 30 seconds, so the bouncer MUST block this one.
//     //     patientClient.post().uri("/api/vectors/calculateMagnitude")
//     //             .headers(headers -> headers.setBasicAuth("admin", "vector-secret-123"))
//     //             .exchange() 
//     //             .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS) 
//     //             .expectBody() 
//     //             .jsonPath("$.error").isEqualTo("Too Many Requests"); 
//     // }

// //     @Test
// //     void testSpammingServer_shouldTriggerRateLimiter429() {
// //         // Give the test robot 10 seconds of patience to account for the server's cold start!
// //         WebTestClient patientClient = webClient.mutate()
// //                 .responseTimeout(Duration.ofSeconds(10))
// //                 .build();

// //         // 1. Send Request #1 (Warms up the server and spends the first token)
// //         patientClient.post().uri("/api/vectors/calculateMagnitude")
// //                 .headers(headers -> headers.setBasicAuth("admin", "vector-secret-123"))
// //                 .exchange(); // We don't check the status here, because Port 8081 isn't running in the test environment!

// //         // 2. Send Request #2 immediately after (Fired in milliseconds!)
// //         patientClient.post().uri("/api/vectors/calculateMagnitude")
// //                 .headers(headers -> headers.setBasicAuth("admin", "vector-secret-123"))
// //                 .exchange() 
// //                 .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS) 
// //                 .expectBody() 
// //                 .jsonPath("$.error").isEqualTo("Too Many Requests"); 
// //     }
// }