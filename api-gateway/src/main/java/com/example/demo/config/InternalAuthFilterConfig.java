package com.example.demo.config;

import org.springframework.cloud.gateway.server.mvc.filter.SimpleFilterSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Registers InternalAuthFilterFunctions as a Spring Cloud Gateway Server WebMVC SimpleFilterSupplier bean so - SignInternalAuth resolves in application.yml.
@Configuration
public class InternalAuthFilterConfig {

    @Bean
    public SimpleFilterSupplier internalAuthFilterSupplier() {
        return new SimpleFilterSupplier(InternalAuthFilterFunctions.class);
    }
}