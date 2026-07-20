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
public class LocalRateLimiterFilter implements GlobalFilter, Ordered {

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

    // private final ConcurrentHashMap<String, Instant> requestLog = new ConcurrentHashMap<>();

    // @Override
    // public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    //     return exchange.getPrincipal().flatMap(principal -> {
    //         String username = principal.getName();
    //         Instant now = Instant.now();
    //         Instant lastRequest = requestLog.getOrDefault(username, Instant.EPOCH);

    //         // THE RULE: If their last request was less than 2000 milliseconds ago, block them gracefully!
    //         if (now.toEpochMilli() - lastRequest.toEpochMilli() < 2000) {
    //             // THE FIX: Return a proper WebFlux error signal instead of abruptly killing the connection
    //             return Mono.<Void>error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Slow down! You must wait 2 seconds between requests."));
    //         }

    //         requestLog.put(username, now);
    //         return chain.filter(exchange);
            
    //     }).switchIfEmpty(chain.filter(exchange)); 
    // }

    @Override
    public int getOrder() {
        return -1; 
    }
}