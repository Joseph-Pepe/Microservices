package com.example.demo.config;

// 1. SERVLET IMPORTS
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// 2. STANDARD SPRING IMPORTS
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 3. JAVA UTILS
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Objects;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// ======================================================================================
// APPROACH #3 (BEST): DISTRIBUTED SYSTEMS SPRING CLOUD GATEWAY MVC (WITH VIRTUAL THEADS)
// ======================================================================================
/* 
    - Distributed memory model using a centralized redis cluster (or database) that all Gateway clones check.
    - Gateway is stateless, if you kill Port 8080 and boot up 8089 it will pick up the user's rate limiting history from Redis.
    - Redis contains a TTL eviction to eliminate any user's rate-limiting record after 30-seconds of inactivity.
    - Vaporizes the key if Redis does not hear from the user again in 30 seconds.
    - Virtual Threads handles I/O blocking and will automatically yield the CPU during these Redis network calls.
*/

/**
 * Tier-1 Enterprise Distributed Rate Limiter (Valkey 8 Lua Primary + Circuit-Broken Local Fallback).
 * - Normal Mode: Single-hop atomic Valkey/Redis EVALSHA script with PEXPIRE.
 * - Degraded Mode: Lock-free Circuit Breaker trips after 5 consecutive Redis errors, bypassing dead network sockets for 15s and enforcing a bounded in-memory sliding window per pod.
 */
@Component
public class DistributedRateLimiterFilter extends OncePerRequestFilter {
    /**
     * Enterprise Distributed Sliding-Window Rate Limiter (Java 21 Virtual Threads + Redis Lua).
     * - Executes atomically in a single network hop via cached Redis EVALSHA.
     * - Uses lock-free ThreadLocalRandom to prevent Virtual Thread pinning under C10K load.
     * - Emits standard X-RateLimit-Limit, X-RateLimit-Remaining, and Retry-After HTTP headers.
     * - Fails OPEN if the Redis cluster is unreachable to guarantee high availability.
     */

    private static final Logger log = LoggerFactory.getLogger(DistributedRateLimiterFilter.class);

    // Bounded local fallback constants to prevent OutOfMemoryError during prolonged Redis outages
    private static final int MAX_LOCAL_TRACKED_USERS = 10_000;
    private static final int CIRCUIT_FAILURE_THRESHOLD = 5;
    private static final long CIRCUIT_OPEN_COOLDOWN_MS = 15_000L;

    // Inject the STANDARD (blocking) StringRedisTemplate
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;

    // Pre-computed immutable configuration strings for zero hot-path allocation
    private final long windowMs;
    private final long rateLimit;
    private final String windowMsStr; // Inject configs from application.yml with safe fallback defaults if they are missing
    private final String rateLimitStr;
    private final String retryAfterSecondsStr;
    private final String exceededResponseMessage;

    // Redis Lua Script: Executes atomic cleanup, count, check, insert, and expire in 1 trip per request (~2ms)
    // Java 21 Text Block: Returns remaining request quota (>= 0) if allowed, or -1 if rate-limited
    private static final String RATE_LIMIT_LUA = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            local clearBefore = now - window

            redis.call('ZREMRANGEBYSCORE', key, 0, clearBefore)
            local currentRequests = redis.call('ZCARD', key)

            if currentRequests >= limit then
                return -1
            end

            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, window)
            return limit - currentRequests - 1
            """; // Rate limit exceeded

    // Lock-free Circuit Breaker state + Bounded Local Sliding Window Fallback
    private final AtomicInteger consecutiveRedisFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenUntilMillis = new AtomicLong(0L);
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> localFallbackWindows = new ConcurrentHashMap<>();

    // Inject Spring's Reactive Redis Driver
    public DistributedRateLimiterFilter(
            StringRedisTemplate redisTemplate,
            @Value("${app.rate-limit.window-ms:30000}") long windowMs,
            @Value("${app.rate-limit.limit:10}") long rateLimit) {
        this.redisTemplate = redisTemplate;
        this.windowMs = windowMs;
        this.rateLimit = rateLimit;
        
        // Pre-compile the script so Spring uses EVALSHA under the hood
        var script = new DefaultRedisScript<Long>();

        // Redis Lua Script: Executes atomic cleanup, count, check, insert, and expire in 1 trip per request (~2ms)
        script.setScriptText(RATE_LIMIT_LUA);
        script.setResultType(Long.class);
        this.rateLimitScript = script;

        long windowSeconds = Math.max(1L, (long) Math.ceil(windowMs / 1000.0));
        this.windowMsStr = String.valueOf(windowMs);
        this.rateLimitStr = String.valueOf(rateLimit);
        this.retryAfterSecondsStr = String.valueOf(windowSeconds);
        this.exceededResponseMessage = "Rate limit exceeded! You are limited to %d requests per %d seconds across the cluster."
                .formatted(rateLimit, windowSeconds);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        // Bypass rate-limiting on CORS preflight requests and cloud health probes
        return HttpMethod.OPTIONS.matches(Objects.requireNonNull(request.getMethod())) || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request, 
        @NonNull HttpServletResponse response, 
        @NonNull FilterChain filterChain) throws ServletException, IOException {

        // Asynchronous Logging
        // if (log.isDebugEnabled()) {
        //     // Virtual Thread Off: Thread[http-nio-8080-exec-1,5,main], Virtual Thread On: VirtualThread[#45, tomcat-handler-1]/runnable
        //     log.debug("Processing request on thread: {}", Thread.currentThread());
        // }

        // 1. Ensure the user is authenticated before checking rate limits
        var principal = request.getUserPrincipal();
        if (principal == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String username = principal.getName();
        long nowMillis = System.currentTimeMillis(); // long thirtySecondsAgoMillis = nowMillis - (30 * 1000);
        long remaining;

        // 1. Check if the Redis Circuit Breaker is currently OPEN
        if (nowMillis < circuitOpenUntilMillis.get()) {
            remaining = evaluateLocalFallback(username, nowMillis);
        } else {
            remaining = evaluateValkeyWithCircuitBreaker(username, nowMillis);
        }

        // Always inform the client of their total window capacity
        response.setHeader("X-RateLimit-Limit", rateLimitStr);

        // 3. THE RULE: If they already have 10 requests, block them!
        if (remaining < 0) {
            log.warn("Rate limit exceeded for user '{}': Maximum allowance limit={} requests per {}ms exceeded", username, rateLimitStr, windowMsStr);
            response.setHeader("X-RateLimit-Remaining", "0");
            response.setHeader("Retry-After", retryAfterSecondsStr);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(exceededResponseMessage);
            return; // Halt the execution chain immediately
        }

        // 4. Pass the request down the chain to the actual route
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        filterChain.doFilter(request, response);


        // 1. Clean up stale timestamps older than 30 seconds
        // redisTemplate.opsForZSet().removeRangeByScore(redisKey, 0.0, thirtySecondsAgoMillis);

        // 2. Count how many requests remain in the window
        // Long currentRequestCount = redisTemplate.opsForZSet().size(redisKey);
        
        // 3. THE RULE: If they already have 10 requests, block them!
        // if (currentRequestCount != null && currentRequestCount >= 10) {
        //     response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        //     response.getWriter().write("Rate limit exceeded! You are limited to 10 requests per 30 seconds across the cluster.");
        //     return; 
        // }

        // 4. If allowed, add the new timestamp to the ZSET
        // redisTemplate.opsForZSet().add(redisKey, uniqueMember, nowMillis);

        // 5. Reset the key's TTL to 30 seconds (EXPIRE) to prevent memory leaks.
        // If the user logs off, Redis automatically deletes the entire key from RAM!
        // redisTemplate.expire(redisKey, Duration.ofSeconds(30));

        // 6. Pass the request down the chain to the actual route
        // filterChain.doFilter(request, response);
    }

    private long evaluateValkeyWithCircuitBreaker(String username, long nowMillis) {
        // Redis Key format: "rate_limit:admin"
        String redisKey = "rate_limit:" + username; 
        // We use a Redis Sorted Set (ZSET). Lock-free unique ZSET member (avoids SecureRandom lock contention in UUID.randomUUID())
        String uniqueMember = nowMillis + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong()); // The "Score" is the timestamp, and the "Value" is a unique string (timestamp + UUID)

        try {
            // 2. Reset the key's TTL to 30 seconds (EXPIRE) to prevent memory leaks.
            // If the user logs off, Redis automatically deletes the entire key from RAM!
            // Single atomic execution in 1 round trip!
            Long scriptResult = redisTemplate.execute(
                    Objects.requireNonNull(rateLimitScript),
                    Objects.requireNonNull(List.of(redisKey)),
                    String.valueOf(nowMillis),
                    windowMsStr,   // Injected via @Value (e.g., 30 second window in ms)
                    rateLimitStr,  // Injected via @Value (e.g., 10 request limit)
                    uniqueMember
            );
            // Reset failure counter on healthy Redis response
            if (consecutiveRedisFailures.get() > 0) {
                consecutiveRedisFailures.set(0);
            }
            final long remaining = (scriptResult != null) ? scriptResult : evaluateLocalFallback(username, nowMillis);
            return remaining;
        } catch (Exception e) {
            int failures = consecutiveRedisFailures.incrementAndGet();
            if (failures >= CIRCUIT_FAILURE_THRESHOLD) {
                circuitOpenUntilMillis.set(nowMillis + CIRCUIT_OPEN_COOLDOWN_MS);

                // REDIS IS DOWN! Log the error immediately, if Redis experiences an outage.
                log.error("CRITICAL: Valkey/Redis failed {} consecutive times! Opening rate-limiter circuit for {}ms and switching to local JVM fallback.",
                        failures, CIRCUIT_OPEN_COOLDOWN_MS, e);
            } else {
                log.warn("Valkey/Redis call failed (attempt {}/{}). Routing user '{}' to local JVM fallback.",
                        failures, CIRCUIT_FAILURE_THRESHOLD, username);
            }
            return evaluateLocalFallback(username, nowMillis);
        }
    }

    /**
     * Lock-free, bounded in-memory sliding window used only when Valkey/Redis is unreachable.
     */
    private long evaluateLocalFallback(String username, long nowMillis) {
        // Guard against heap exhaustion during a DDoS + Redis outage
        if (localFallbackWindows.size() > MAX_LOCAL_TRACKED_USERS && !localFallbackWindows.containsKey(username)) {
            localFallbackWindows.clear();
        }

        var deque = localFallbackWindows.computeIfAbsent(username, k -> new ConcurrentLinkedDeque<>());
        long clearBefore = nowMillis - windowMs;

        // Evict expired timestamps from the head of the deque
        Long oldest;
        while ((oldest = deque.peekFirst()) != null && oldest <= clearBefore) {
            deque.pollFirst();
        }

        int currentCount = deque.size();
        if (currentCount >= rateLimit) {
            return -1L;
        }

        deque.addLast(nowMillis);
        return Math.max(0L, rateLimit - currentCount - 1);
    }
}

// =============================================================
// APPROACH #1 (WORST): SIMPLE DEBOUNCE
// =============================================================
/* 
    - Is a 2-second delay in preventing the client from spamming multiple API calls one after another.
*/

/*
    package com.example.demo.config;

    import org.springframework.cloud.gateway.filter.GatewayFilterChain;
    import org.springframework.cloud.gateway.filter.GlobalFilter;
    import org.springframework.core.Ordered;
    import org.springframework.http.HttpStatus;
    import org.springframework.web.server.ServerWebExchange;
    import org.springframework.web.server.ResponseStatusException;
    import reactor.core.publisher.Mono;

    import java.time.Instant;
    import java.util.concurrent.ConcurrentHashMap;

    public class LocalRateLimiterFilterDebounce implements GlobalFilter, Ordered {
        private final ConcurrentHashMap<String, Instant> requestLog = new ConcurrentHashMap<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            return exchange.getPrincipal().flatMap(principal -> {
                String username = principal.getName();
                Instant now = Instant.now();
                Instant lastRequest = requestLog.getOrDefault(username, Instant.EPOCH);

                // THE RULE: If their last request was less than 2000 milliseconds ago, block them gracefully!
                if (now.toEpochMilli() - lastRequest.toEpochMilli() < 2000) {
                    // THE FIX: Return a proper WebFlux error signal instead of abruptly killing the connection
                    return Mono.<Void>error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Slow down! You must wait 2 seconds between requests."));
                }

                requestLog.put(username, now);
                return chain.filter(exchange);
                
            }).switchIfEmpty(chain.filter(exchange)); 
        }

        @Override
        public int getOrder() {
            return -1; 
        }
    }
*/

// =============================================================
// APPROACH #2 (BETTER): SINGLE-SERVER SPRING CLOUD GATEWAY
// =============================================================
/* 
    - Is an upgraded version of the debounce version (LocalRateLimiterFilterDebounce).
    - Uses Sliding Window Log algorithm.
    - Lives in local memory where each instance maintains its own memory (i.e., JVM memory model).
    - Not optimal b/c client can bypass limit rate (10 requests in 30 seconds) by calling Gateway A 10 times and Gateway B 10 times (totaling 20 requests in 30 seconds).
*/

/*
    package com.example.demo.config;

    import org.springframework.cloud.gateway.filter.GatewayFilterChain;
    import org.springframework.cloud.gateway.filter.GlobalFilter;
    import org.springframework.core.Ordered;
    import org.springframework.http.HttpStatus;
    import org.springframework.stereotype.Component;
    import org.springframework.web.server.ServerWebExchange;
    import org.springframework.web.server.ResponseStatusException;
    import reactor.core.publisher.Mono;

    import java.time.Instant;
    import java.util.Queue;
    import java.util.concurrent.ConcurrentHashMap;
    import java.util.concurrent.ConcurrentLinkedQueue;



    @Component
    public class LocalRateLimiterFilterSingleServer implements GlobalFilter, Ordered {

        // The Bouncer's Notepad now holds a Queue (list) of timestamps for each user 
        private final ConcurrentHashMap<String, Queue<Instant>> requestLog = new ConcurrentHashMap<>();

        // RATE LIMIT: 10 REQUESTS WITHIN 30 SECOND WINDOW.
        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            return exchange.getPrincipal().flatMap(principal -> {
                String username = principal.getName();
                Instant now = Instant.now();
                Instant thirtySecondsAgo = now.minusSeconds(30);

                // 1. Get the user's history queue (create a new one if they are a first-time visitor)
                Queue<Instant> userRequests = requestLog.computeIfAbsent(username, k -> new ConcurrentLinkedQueue<>());

                // 2. Clean up the notepad: Remove any timestamps older than 30 seconds
                // (peek looks at the oldest timestamp, poll removes it)
                while (!userRequests.isEmpty() && userRequests.peek().isBefore(thirtySecondsAgo)) {
                    userRequests.poll();
                }

                // 3. THE RULE: If they still have 10 requests sitting in the 30-second window, block them!
                if (userRequests.size() >= 10) {
                    return Mono.<Void>error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, 
                            "Rate limit exceeded! You are limited to 10 requests per 30 seconds."));
                }

                // 4. If they pass, log the exact time of this new request and open the door
                userRequests.add(now);
                return chain.filter(exchange);
                
            }).switchIfEmpty(chain.filter(exchange)); 
        }

        @Override
        public int getOrder() {
            return -1; 
        }
    }
*/