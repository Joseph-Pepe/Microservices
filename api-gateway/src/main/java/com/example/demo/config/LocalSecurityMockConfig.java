package com.example.demo.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Configuration
@Profile({"local", "test"}) // Strictly isolated to local development and automated test suites // CRITICAL: This ensures this mock never accidentally deploys to production
public class LocalSecurityMockConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalSecurityMockConfig.class);
    private static final String DEFAULT_TEST_SUBJECT = "local-admin-tester";

    @PostConstruct
    void logSecurityWarning() {
        log.warn("⚠️ [LOCAL SECURITY MOCK ACTIVE] Cryptographic JWT signature verification is bypassed. DO NOT use in production.");
    }

    /**
     * This intercepts the JWT validation process. 
     * Instead of checking cryptographic signatures against Auth0, it blindly accepts 
     * whatever token you send and fabricates a logged-in user.
     */
    
    // Step #1: mvn spring-boot:run -Dspring-boot.run.profiles=local
    // Step #2: curl -X GET http://localhost:8080/api/vectors/math -H "Authorization: Bearer literally-anything-i-want"

    /**
     * High-speed local JwtDecoder stub.
     * - Defaults 'sub' to "local-admin-tester" (matching GatewaySecurityAndRateLimitTests).
     * - Supports dynamic multi-user testing if the token starts with "user:" (e.g., "Bearer user:alice").
     */
    @Bean
    public JwtDecoder localJwtDecoder() {
        return token -> {
            var now = Instant.now();
            var subject = (token != null && token.startsWith("user:"))
                    ? token.substring(5)
                    : DEFAULT_TEST_SUBJECT;

            return new Jwt(
                    token,
                    now,
                    now.plusSeconds(3600),
                    Map.of("alg", "none", "typ", "JWT"),
                    Map.of(
                            "sub", subject,
                            "scope", "read write",
                            "roles", List.of("ROLE_ADMIN", "ROLE_USER"),
                            "iss", "https://local.lyriastudio.ai/",
                            "jti", UUID.randomUUID().toString()
                    )
            );
        };
        // return token -> Jwt.withTokenValue(token)
        //         .header("alg", "none")
        //         // The 'sub' (subject) claim is what Spring uses for principal.getName()
        //         // This string ("local-admin-tester") will become your Redis Rate Limiting Key!
        //         .claim("sub", "local-admin-tester") 
        //         .issuedAt(Instant.now())
        //         .expiresAt(Instant.now().plusSeconds(3600))
        //         .build();
    }
}