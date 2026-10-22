package com.example.demo; 

import com.example.demo.config.DistributedRateLimiterFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local") // 1. CRITICAL: Boots local profile to enable LocalSecurityMockConfig!
class GatewaySecurityAndRateLimitTests {

    @Autowired
    private WebTestClient webClient; 

    // Spring Boot 4.0+ spy annotation (use @SpyBean if compiling against Spring Boot 3.2.x)
    @MockitoSpyBean
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DistributedRateLimiterFilter rateLimiterFilter;

    @BeforeEach
    void resetRateLimiterState() {
        // 1. Clear any Mockito outage stubs from previous tests
        Mockito.reset(redisTemplate);

        // 2. Reset in-memory Circuit Breaker & Local Fallback state between tests
        ((AtomicInteger) Objects.requireNonNull(
                ReflectionTestUtils.getField(rateLimiterFilter, "consecutiveRedisFailures"))).set(0);
        ((AtomicLong) Objects.requireNonNull(
                ReflectionTestUtils.getField(rateLimiterFilter, "circuitOpenUntilMillis"))).set(0L);
        ((Map<?, ?>) Objects.requireNonNull(
                ReflectionTestUtils.getField(rateLimiterFilter, "localFallbackWindows"))).clear();

        try {
            // 3. Ensure every test run starts with a clean slate in Valkey/Redis
            redisTemplate.delete(java.util.List.of("rate_limit:local-admin-tester", "rate_limit:outage-tester"));
        } catch (Exception ignored) {
            // Ignore if running the test suite without a local Redis container
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
    void testSpammingServer_shouldTriggerRateLimiter429() {
        var patientClient = webClient.mutate()
                .responseTimeout(Objects.requireNonNull(Duration.ofSeconds(10)))
                .build();

        // 2. Rapidly fire 10 valid requests WITH bearer tokens using modern Java IntStream
        IntStream.range(0, 10).forEach(i ->
            patientClient.post().uri("/api/vectors/calculateMagnitude")
                    .headers(headers -> headers.setBearerAuth("mock-test-token-string")) // Triggers mock decoder!
                    .exchange()
        );

        // 3. An 11th request must be blocked by the atomic Valkey/Redis Lua script.
        // The Lua script has 10 entries for "local-admin-tester" in Redis, so it will block this one.
        patientClient.post().uri("/api/vectors/calculateMagnitude")
                .headers(headers -> headers.setBearerAuth("mock-test-token-string"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("X-RateLimit-Limit", "10")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectHeader().valueEquals("Retry-After", "30")
                .expectBody(String.class)
                // Match the exact plain-text error message written by your response.getWriter().write()
                .isEqualTo("Rate limit exceeded! You are limited to 10 requests per 30 seconds across the cluster.");
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
                .execute(any(RedisScript.class), anyList(), any(), any(), any(), any());

        // 2. Fire 10 requests as "user:outage-tester" (supported by LocalSecurityMockConfig:
        //    - Requests 1-5 fail on Redis, trip the Circuit Breaker OPEN, and fall back to local JVM memory.
        //    - Requests 6-10 short-circuit Redis completely (0 network calls) and use local JVM memory.
        IntStream.range(0, 10).forEach(i ->
            patientClient.post().uri("/api/vectors/calculateMagnitude")
                    .headers(headers -> headers.setBearerAuth("user:outage-tester"))
                    .exchange()
        );

        // 3. The 11th request must STILL be blocked (HTTP 429) by the Bounded Local Fallback!
        patientClient.post().uri("/api/vectors/calculateMagnitude")
                .headers(headers -> headers.setBearerAuth("user:outage-tester"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("X-RateLimit-Limit", "10")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectHeader().valueEquals("Retry-After", "30")
                .expectBody(String.class)
                .isEqualTo("Rate limit exceeded! You are limited to 10 requests per 30 seconds across the cluster.");

        // 4. PROVE CIRCUIT BREAKER TRIPPED: Out of 11 HTTP requests, Redis was only called 5 times!
        verify(redisTemplate, times(5))
                .execute(any(RedisScript.class), anyList(), any(), any(), any(), any());
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