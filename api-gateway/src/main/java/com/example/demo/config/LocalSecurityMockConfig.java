package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;

@Configuration
@Profile("local") // CRITICAL: This ensures this mock never accidentally deploys to production
public class LocalSecurityMockConfig {

    /**
     * This intercepts the JWT validation process. 
     * Instead of checking cryptographic signatures against Auth0, it blindly accepts 
     * whatever token you send and fabricates a logged-in user.
     */
    
    // Step #1: mvn spring-boot:run -Dspring-boot.run.profiles=local
    // Step #2: curl -X GET http://localhost:8080/api/vectors/math -H "Authorization: Bearer literally-anything-i-want"
    @Bean
    public JwtDecoder localJwtDecoder() {
        return token -> Jwt.withTokenValue(token)
                .header("alg", "none")
                // The 'sub' (subject) claim is what Spring uses for principal.getName()
                // This string ("local-admin-tester") will become your Redis Rate Limiting Key!
                .claim("sub", "local-admin-tester") 
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}