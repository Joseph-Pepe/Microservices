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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.lang.NonNull;

// 3. JAVA UTILS
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

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
@Component
public class DistributedRateLimiterFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DistributedRateLimiterFilter.class);

    // Inject the STANDARD (blocking) StringRedisTemplate
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;

    // Inject configs from application.yml with safe fallback defaults if they are missing
    @Value("${app.rate-limit.window-ms:30000}")
    private String windowMs;

    @Value("${app.rate-limit.limit:10}")
    private String rateLimit;

    // Redis Lua Script: Executes atomic cleanup, count, check, insert, and expire in 1 trip per request (~2ms)
    private static final String RATE_LIMIT_LUA = 
        "local key = KEYS[1] " +
        "local now = tonumber(ARGV[1]) " +
        "local window = tonumber(ARGV[2]) " +
        "local limit = tonumber(ARGV[3]) " +
        "local member = ARGV[4] " +
        "local clearBefore = now - window " +
        
        "redis.call('ZREMRANGEBYSCORE', key, 0, clearBefore) " +
        "local currentRequests = redis.call('ZCARD', key) " +
        
        "if currentRequests >= limit then " +
            "return 0 " + // Rate limit exceeded
        "end " +
        
        "redis.call('ZADD', key, now, member) " +
        "redis.call('EXPIRE', key, math.ceil(window / 1000)) " +
        "return 1"
    ;

    // Inject Spring's Reactive Redis Driver
    public DistributedRateLimiterFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        
        // Pre-compile the script so Spring uses EVALSHA under the hood
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();

        // Redis Lua Script: Executes atomic cleanup, count, check, insert, and expire in 1 trip per request (~2ms)
        script.setScriptText(RATE_LIMIT_LUA);
        script.setResultType(Long.class);
        this.rateLimitScript = script;
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request, 
        @NonNull HttpServletResponse response, 
        @NonNull FilterChain filterChain) throws ServletException, IOException {

        // Production-ready Asynchronous Logging
        if (log.isDebugEnabled()) {
            // Virtual Thread Off: Thread[http-nio-8080-exec-1,5,main], Virtual Thread On: VirtualThread[#45, tomcat-handler-1]/runnable
            log.debug("Processing request on thread: {}", Thread.currentThread());
        }

        // 1. Ensure the user is authenticated before checking rate limits
        if (request.getUserPrincipal() == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String username = request.getUserPrincipal().getName();
        String redisKey = "rate_limit:" + username; // Redis Key format: "rate_limit:admin"
        long nowMillis = Instant.now().toEpochMilli();
        // long thirtySecondsAgoMillis = nowMillis - (30 * 1000);

        // We use a Redis Sorted Set (ZSET). 
        String uniqueMember = nowMillis + "-" + UUID.randomUUID().toString().substring(0, 8); // The "Score" is the timestamp, and the "Value" is a unique string (timestamp + UUID)

        // Single atomic execution in 1 round trip!
        Long result = redisTemplate.execute(
            Objects.requireNonNull(rateLimitScript),
            Objects.requireNonNull(Collections.singletonList(redisKey)),
            String.valueOf(nowMillis),
            windowMs,   // Injected via @Value (e.g., 30 second window in ms)
            rateLimit,  // Injected via @Value (e.g., 10 request limit)
            uniqueMember
        );

        // 3. THE RULE: If they already have 10 requests, block them!
        if (Long.valueOf(0).equals(result)) {
            log.warn("Rate limit triggered for user: {}! Maximum allowance of {} requests per {}ms exceeded.", username, rateLimit, windowMs);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("Rate limit exceeded! You are limited to 10 requests per 30 seconds across the cluster.");
            return;  // Halt the execution chain immediately
        }

        // 4. Pass the request down the chain to the actual route
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