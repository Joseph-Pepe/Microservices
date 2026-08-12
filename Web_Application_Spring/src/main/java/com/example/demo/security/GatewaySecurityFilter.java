package com.example.demo.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * THE ZERO-TRUST PERIMETER FILTER
 * Intercepts HTTP requests before they touch any RestControllers.
 * This runs before any controller is triggered. It guarantees that NO traffic 
 * can access this microservice unless it carries the Gateway's stamp of approval.
 */
@Component
public class GatewaySecurityFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // 1. ALLOW LIST: Let Swagger/OpenAPI internal documentation bypass the filter! Otherwise, you won't be able to view your own API docs locally.
        if (path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. THE PERIMETER CHECK: Intercept Gateway Secret Header
        String gatewayAuth = request.getHeader("X-Gateway-Validated");

        // 3. THE DROP: If it's missing or false, drop the connection immediately! Reject unvalidated requests immediately
        if (gatewayAuth == null || !gatewayAuth.equals("true")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Forbidden\", \"message\": \"Internal Perimeter Violation: Direct endpoint microservice requests are blocked.\"}");
            return; 
        }

        // 4. PROGRESS: If the header is valid, let the request through to the Controller! Forward to target controller mapping
        filterChain.doFilter(request, response);
    }
}