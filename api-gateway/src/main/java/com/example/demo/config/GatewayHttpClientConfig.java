package com.example.demo.config;

import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * Backs Spring Cloud Gateway Server WebMVC's outbound RestClient with Java 21/25+ Virtual Threads.
 * Ensures blocking upstream reads and long-lived AI SSE streams unmount from OS carrier threads in nanoseconds.
 * Every outbound proxy call and AI SSE stream made by Spring Cloud Gateway MVC runs on a dedicated Virtual Thread executor with HTTP/2 multiplexing.
 */
@Configuration
public class GatewayHttpClientConfig {

    @Bean
    public RestClientCustomizer virtualThreadRestClientCustomizer() {
        var virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        var jdkHttpClient = HttpClient.newBuilder()
                .executor(virtualThreadExecutor)
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        var requestFactory = new JdkClientHttpRequestFactory(jdkHttpClient, virtualThreadExecutor);
        requestFactory.setReadTimeout(Duration.ofSeconds(15));

        return restClientBuilder -> restClientBuilder.requestFactory(requestFactory);
    }
}