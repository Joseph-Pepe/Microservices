package com.example.demo.config;

import com.example.demo.security.InternalAuthTokenService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Custom Spring Cloud Gateway Server WebMVC HandlerFilterFunction (SignInternalAuth) that strips any untrusted X-Gateway-Validated / X-Internal-Auth headers and injects the freshly signed 30s JWT carrying sub, roles, jti, and iss.
public abstract class InternalAuthFilterFunctions {

    private InternalAuthFilterFunctions() {
    }

    public static HandlerFilterFunction<ServerResponse, ServerResponse> signInternalAuth() {
        return HandlerFilterFunction.ofRequestProcessor(request -> {
            var servletContext = request.servletRequest().getServletContext();
            var tokenService = WebApplicationContextUtils
                    .getRequiredWebApplicationContext(servletContext)
                    .getBean(InternalAuthTokenService.class);

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String subject = resolveSubject(authentication);
            List<String> roles = resolveRoles(authentication);
            String signedToken = tokenService.mintToken(subject, roles);

            return ServerRequest.from(request)
                    .headers(headers -> {
                        headers.remove(InternalAuthTokenService.LEGACY_HEADER_NAME);
                        headers.remove(InternalAuthTokenService.HEADER_NAME);
                        headers.set(InternalAuthTokenService.HEADER_NAME, signedToken);
                    })
                    .build();
        });
    }

    private static String resolveSubject(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return "anonymous";
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            String sub = jwtAuth.getToken().getSubject();
            if (sub != null && !sub.isBlank()) {
                return sub;
            }
        }
        String name = authentication.getName();
        return (name == null || name.isBlank()) ? "anonymous" : name;
    }

    private static List<String> resolveRoles(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return List.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            List<String> jwtRoles = jwtAuth.getToken().getClaimAsStringList("roles");
            if (jwtRoles != null) {
                roles.addAll(jwtRoles);
            }
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (authority.getAuthority() != null && !authority.getAuthority().isBlank()) {
                roles.add(authority.getAuthority());
            }
        }
        return new ArrayList<>(roles);
    }
}