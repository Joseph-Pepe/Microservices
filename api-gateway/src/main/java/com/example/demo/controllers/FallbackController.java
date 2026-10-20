package com.example.demo.controllers;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// import java.util.HashMap;
// import java.util.Map;

import java.net.URI;
import java.time.Instant;

// When the circuit breaker trips (or a timeout is reached), the gateway intercepts the crash and reroutes the user to a local fallback path "forward:/fallback" instead of throwing an ugly 500 Internal Service Error.
@RestController
public class FallbackController {

    // @RequestMapping("/fallback")
    // public ResponseEntity<Map<String, Object>> serviceFallback() {
    //     Map<String, Object> response = new HashMap<>();
    //     response.put("error", "Service Unavailable");
    //     response.put("message", "The AI generation service is experiencing heavy traffic and is temporarily unavailable to serve your request. Please try again in a few moments.");
    //     response.put("status", 503);
        
    //     // Return a graceful 503 so the frontend knows to display a "Try again later" UI
    //     return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    // }

    @RequestMapping("/fallback")
    public ResponseEntity<ProblemDetail> serviceFallback(HttpServletRequest request) {
        // RFC 9457 Standard ProblemDetail (Zero-overhead, strongly-typed error contract)
        var problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "The AI generation service is experiencing heavy traffic and is temporarily unavailable. Please retry shortly."
        );
        problem.setTitle("Service Unavailable");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("retryAfterSeconds", 20);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "20")
                .body(problem);
    }
}
