package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
            // 1. Disable CSRF (Standard practice for REST APIs)
            .csrf(csrf -> csrf.disable()) 
            
            // 2. Define our routing rules
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/api/vectors/ping").permitAll() // Let anyone ping the server to see if it's alive
                .pathMatchers("/api/vectors/**").authenticated() // Force a password for all actual math operations
                .anyExchange().denyAll() // Block anything else we haven't explicitly thought of
            )
            
            // 3. Tell Spring to use standard HTTP Basic Authentication (Username/Password in the header)
            .httpBasic(httpBasic -> {}); 

        return http.build();
    }

    // Create a hardcoded user in memory so we can test it immediately
    @Bean
    public MapReactiveUserDetailsService userDetailsService() {
        UserDetails admin = User.builder()
            .username("admin")
            .password("{noop}vector-secret-123") // {noop} tells Spring not to expect a hashed database password for this test
            .roles("ADMIN")
            .build();
            
        return new MapReactiveUserDetailsService(admin);
    }
    
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
            .map(principal -> principal.getName()) // Track tokens by their login username!
            .defaultIfEmpty("anonymous");          // If they aren't logged in, group them in an anonymous bucket
    }
    
}