package com.example.demo.config;

import com.example.demo.resilience.AdaptiveConcurrencyLimiter;
import com.example.demo.resilience.AdaptiveConcurrencyLimiter.AdmissionResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Workload-isolated Adaptive Concurrency Load Shedding Filter (Order -98, after Spring Security & Valkey GCRA).
 * - Protects downstream AI/compute pools from concurrency collapse during RTT inflation.
 * - Keeps fast REST traffic isolated from long-lived SSE streams (/api/vectors/stream/**).
 * - Returns RFC 9457 ProblemDetail (503) + Retry-After when shedding excess concurrency.
 */
public class AdaptiveConcurrencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveConcurrencyFilter.class);

    private final boolean enabled;
    private final AdaptiveConcurrencyLimiter restLimiter;
    private final AdaptiveConcurrencyLimiter streamLimiter;
    private final JsonMapper jsonMapper;

    public AdaptiveConcurrencyFilter(
            boolean enabled,
            AdaptiveConcurrencyLimiter restLimiter,
            AdaptiveConcurrencyLimiter streamLimiter) {
        this.enabled = enabled;
        this.restLimiter = restLimiter;
        this.streamLimiter = streamLimiter;
        this.jsonMapper = JsonMapper.builder().build();
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        String path = request.getRequestURI();
        return HttpMethod.OPTIONS.matches(Objects.requireNonNull(request.getMethod()))
                || path.startsWith("/actuator/")
                || "/fallback".equals(path);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        AdaptiveConcurrencyLimiter limiter = selectPartition(request.getRequestURI());
        AdmissionResult admission = limiter.tryAcquire();

        if (!admission.admitted()) {
            int retryAfter = limiter.getRetryAfterSeconds();
            log.warn("Shedding load on partition '{}' for '{}' (inFlight={}, limit={}, retryAfter={}s)",
                    limiter.getPartitionName(), request.getRequestURI(), admission.inFlight(), admission.limit(), retryAfter);

            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setHeader("X-Concurrency-Limit", String.valueOf(admission.limit()));
            response.setHeader("X-Concurrency-InFlight", String.valueOf(admission.inFlight()));

            Map<String, Object> problem = new LinkedHashMap<>();
            problem.put("type", "about:blank");
            problem.put("title", "Service Unavailable");
            problem.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
            problem.put("detail", "Gateway is shedding excess concurrency due to downstream latency inflation. Please retry shortly.");
            problem.put("instance", request.getRequestURI());
            problem.put("timestamp", Instant.now().toString());
            problem.put("retryAfterSeconds", retryAfter);
            problem.put("concurrencyLimit", admission.limit());
            problem.put("inFlight", admission.inFlight());

            response.getWriter().write(jsonMapper.writeValueAsString(problem));
            return;
        }

        response.setHeader("X-Concurrency-Limit", String.valueOf(admission.limit()));
        response.setHeader("X-Concurrency-InFlight", String.valueOf(admission.inFlight()));

        long startNanos = System.nanoTime();
        boolean completedNormally = false;
        try {
            filterChain.doFilter(request, response);
            completedNormally = true;
        } finally {
            long rttNanos = System.nanoTime() - startNanos;
            int status = response.getStatus();
            if (!completedNormally || status == HttpStatus.GATEWAY_TIMEOUT.value() || status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                limiter.releaseDropped(rttNanos, admission.inFlight());
            } else if (status >= 200 && status < 400) {
                limiter.releaseSuccess(rttNanos, admission.inFlight());
            } else {
                // 4xx client errors or fast 500s are ignored so they neither inflate nor falsely shrink RTT
                limiter.releaseIgnored();
            }
        }
    }

    private AdaptiveConcurrencyLimiter selectPartition(String uri) {
        return uri.startsWith("/api/vectors/stream/") ? streamLimiter : restLimiter;
    }

    public AdaptiveConcurrencyLimiter getRestLimiter() {
        return restLimiter;
    }

    public AdaptiveConcurrencyLimiter getStreamLimiter() {
        return streamLimiter;
    }

    public void resetForTesting() {
        restLimiter.reset();
        streamLimiter.reset();
    }
}