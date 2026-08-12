package com.example.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

// ========================================================
// SPRING CLOUD GATEWAY MVC SECURITY (WITH VIRTUAL THREADS)
// ========================================================
/*
    - Replaces WebFlux Security by providing massive scalability and high throughput of the reactive model, making it easier to write, read and debug.
*/
@Configuration
@EnableWebSecurity 
public class SecurityConfig {

    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 1. ENABLE CORS Integration with Spring Security
            .cors(Customizer.withDefaults())

            // 2. Disable CSRF and browser form logins (Standard for stateless REST APIs)
            .csrf(csrf -> csrf.disable())

            // 3. The Firewall's Access Control List (ACL)! Define routing authorization rules (Enhances Spring Security)
            .authorizeHttpRequests(auth -> auth 
                // Let the browser's OPTIONS preflight checks pass through unauthenticated
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.FORWARD, jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()

                .requestMatchers("/api/vectors/ping").permitAll() // Let anyone ping the server to see if it's alive
                .requestMatchers("/api/vectors/**").authenticated() // Require valid JWT for math operations
                .anyRequest().denyAll() // Block anything else we haven't explicitly thought of
            )

            // 4. OAuth2 Resource Server (JWT Token)
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(Customizer.withDefaults())
            );

        return http.build();
    }

    /**
     * Define the exact CORS rules the Gateway will enforce.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Inject the allowed origins from application.yml (e.g., React, Angular)
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        
        // Standard REST methods
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        
        // Allow the browser to send the Authorization header (containing your JWT)
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        
        // Optional: Expose custom headers back to the frontend (if your Gateway generates any)
        // configuration.setExposedHeaders(List.of("X-RateLimit-Remaining"));
        
        // Apply this configuration to every route passing through the Gateway
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        
        return source;
    }
}

// ============================================
// WEBFLUX SECURITY (REACTIVE APPROACH)
// ============================================
/*
    - Allows a small server to handle tens of thousands of concurrent users since its not wasting OS threads. 
    - Useful for Continuous data streams over long lived connections (e.g., real-time multiplayer video game backends).
    - Automatically supports functional chaining for fetch data from multiple APIs simultaneously and backpressure handling for slower connections.
*/

/*
    package com.example.demo.config;

    import org.springframework.context.annotation.Bean;
    import org.springframework.context.annotation.Configuration;
    import org.springframework.security.config.Customizer;
    import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
    import org.springframework.security.config.web.server.ServerHttpSecurity;
    import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
    import org.springframework.security.core.userdetails.User;
    import org.springframework.security.core.userdetails.UserDetails;
    import org.springframework.security.web.server.SecurityWebFilterChain;

    import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;

    @Configuration
    @EnableWebFluxSecurity
    public class SecurityConfig {

        @Bean
        public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
            http
                // 1. Disable CSRF and browser form logins for stateless REST API routing
                .csrf(csrf -> csrf.disable()) 

                // 2. Disable the browser HTML form login interface to theown a clean 401 unauthorized headers to tools like curl or web applications.
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())

                // 3. The Firewall's Access Control List (ACL)! Define routing authorization rules (Enhances Spring Security WebFlux)
                .authorizeExchange(exchanges -> exchanges
                    .pathMatchers("/api/vectors/ping").permitAll() // Let anyone ping the server to see if it's alive
                    .pathMatchers("/api/vectors/**").authenticated() // Require valid JWT for math operations
                    .anyExchange().denyAll() // Block anything else we haven't explicitly thought of
                )

                // 4. The Firewall's Scanning Mechanism! Configure a stateless JWT OAuth2 Resource Server.
                .oauth2ResourceServer(oauth2 -> oauth2
                    .jwt(Customizer.withDefaults())
                );

            return http.build();
        }

        // Create a hardcoded user in memory so we can test it immediately (i.e., ideal for local integration testing, but not production environments).
        @Bean
        public MapReactiveUserDetailsService userDetailsService() {
            UserDetails admin = User.builder()
                .username("admin")
                .password("{noop}vector-secret-123") // {noop} tells Spring not to expect a hashed database password for this test
                .roles("ADMIN")
                .build();
                
            return new MapReactiveUserDetailsService(admin);
        }
        
        // 4. THE REDIS BRIDGE: Works seamlessly with JWTs! Feeds the user identity into the Redis rate limiter.
        @Bean
        public KeyResolver userKeyResolver() {
            return exchange -> exchange.getPrincipal()
                .map(principal -> principal.getName()) // Automatically pulls the 'sub' (username) claim from the JWT! Track tokens by their login username! 
                .defaultIfEmpty("anonymous");          // If they aren't logged in, group them in an anonymous bucket
        }
        
    }
*/