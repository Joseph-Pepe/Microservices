package com.example.demo.config;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

// =============================================================
// APPROACH #3 (BEST): DISTRIBUTED SYSTEMS SPRING CLOUD GATEWAY
// =============================================================
/* 
    - Distributed memory model using a centralized redis cluster (or database) that all Gateway clones check.
    - Gateway is stateless, if you kill Port 8080 and boot up 8089 it will pick up the user's rate limiting history from Redis.
    - Redis contains a TTL eviction to eliminate any user's rate-limiting record after 30-seconds of inactivity.
    - Vaporizes the key if Redis does not hear from the user again in 30 seconds.
*/

@Component
public class DistributedRateLimiterFilter implements GlobalFilter, Ordered {

    private final ReactiveStringRedisTemplate redisTemplate;

    // Inject Spring's Reactive Redis Driver
    public DistributedRateLimiterFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal().flatMap(principal -> {
            String username = principal.getName();
            
            // Redis Key format: "rate_limit:admin"
            String redisKey = "rate_limit:" + username;
            
            long nowMillis = Instant.now().toEpochMilli();
            long thirtySecondsAgoMillis = nowMillis - (30 * 1000);

            // We use a Redis Sorted Set (ZSET). 
            // The "Score" is the timestamp, and the "Value" is a unique string (timestamp + UUID)
            String uniqueMember = nowMillis + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);

            // 1. Clean up stale timestamps older than 30 seconds (ZREMRANGEBYSCORE)
            return redisTemplate.opsForZSet()
                    .removeRangeByScore(redisKey, org.springframework.data.domain.Range.closed(0.0, (double) thirtySecondsAgoMillis))
                    .then(
                        // 2. Count how many requests remain in the window (ZCARD) - Constant/Logarithmic Time!
                        redisTemplate.opsForZSet().size(redisKey)
                    )
                    .flatMap(currentRequestCount -> {
                        // 3. THE RULE: If they already have 10 requests in Redis, block them!
                        if (currentRequestCount >= 10) {
                            return Mono.<Void>error(new ResponseStatusException(
                                    HttpStatus.TOO_MANY_REQUESTS, 
                                    "Rate limit exceeded! You are limited to 10 requests per 30 seconds across the cluster."
                            ));
                        }

                        // 4. If allowed, add the new timestamp to the ZSET (ZADD)
                        return redisTemplate.opsForZSet()
                                .add(redisKey, uniqueMember, (double) nowMillis)
                                .then(
                                    // 5. Reset the key's TTL to 30 seconds (EXPIRE) to prevent memory leaks.
                                    // If the user logs off, Redis automatically deletes the entire key from RAM!
                                    redisTemplate.expire(redisKey, Duration.ofSeconds(30))
                                )
                                .then(chain.filter(exchange));
                    });

        }).switchIfEmpty(chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        return -1; // Execute at the very front of the Gateway security chain
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