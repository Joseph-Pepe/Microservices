package com.example.demo.security;

import com.example.demo.security.InternalAuthTokenVerifier.InternalPrincipal;
import com.example.demo.security.InternalAuthTokenVerifier.InvalidInternalAuthException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * THE ZERO-TRUST PERIMETER FILTER
 * Intercepts HTTP requests before they touch any RestControllers.
 * This runs before any controller is triggered. It guarantees that NO traffic 
 * can access this microservice unless it carries the Gateway's stamp of approval.
 */

// Rejects missing X-Internal-Auth (including legacy X-Gateway-Validated: true alone) with 403 Forbidden, rejects forged/tampered/expired X-Internal-Auth with 401 Unauthorized, and exposes /actuator/health, /swagger-ui, and /v3/api-docs.
@Component
public class GatewaySecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewaySecurityFilter.class);
    private final InternalAuthTokenVerifier tokenVerifier;

    public GatewaySecurityFilter(InternalAuthTokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    @org.springframework.beans.factory.annotation.Value("${springdoc.api-docs.enabled:true}")
    private boolean apiDocsEnabled;

    @org.springframework.beans.factory.annotation.Value("${springdoc.swagger-ui.enabled:true}")
    private boolean swaggerUiEnabled;


    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        // 1. ALLOW LIST: Let Swagger/OpenAPI internal documentation bypass the filter! Otherwise, you won't be able to view your own API docs locally.
        return (swaggerUiEnabled && path.startsWith("/swagger-ui"))
                || (apiDocsEnabled && path.startsWith("/v3/api-docs"))
                || "/actuator/health".equals(path)
                || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        // 2. THE PERIMETER CHECK: Intercept Gateway Secret Header
        String internalAuthToken = request.getHeader(InternalAuthTokenVerifier.HEADER_NAME);

        // 3. THE DROP: If it's missing or false, drop the connection immediately! Reject unvalidated requests immediately
        if (internalAuthToken == null || internalAuthToken.isBlank()) {
            writeError(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "Forbidden",
                    "Internal Perimeter Violation: Missing X-Internal-Auth gateway token."
            );
            return;
        }

        try {
            InternalPrincipal principal = tokenVerifier.verify(internalAuthToken);
            request.setAttribute(InternalAuthTokenVerifier.PRINCIPAL_ATTRIBUTE, principal);

            // 4. PROGRESS: If the header is valid, let the request through to the Controller! Forward to target controller mapping
            filterChain.doFilter(request, response);
        } catch (InvalidInternalAuthException e) {
            log.warn("Rejected invalid X-Internal-Auth token on '{}': {}", request.getRequestURI(), e.getMessage());
            writeError(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Unauthorized",
                    "Internal Perimeter Violation: " + e.getMessage()
            );
        }
    }

    private static void writeError(HttpServletResponse response, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String sanitizedMessage = message.replace("\"", "\\\"");
        response.getWriter().write("{\"error\": \"%s\", \"message\": \"%s\"}".formatted(error, sanitizedMessage));
    }
}