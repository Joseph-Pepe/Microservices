package com.example.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backs Spring Cloud Gateway Server WebMVC's outbound RestClient with Java 21/25+ Virtual Threads.
 * Ensures blocking upstream reads and long-lived AI SSE streams.
 * Every outbound proxy call and AI SSE stream made by Spring Cloud Gateway MVC runs on a dedicated Virtual Thread executor with HTTP/2 multiplexing.
 */
@Configuration
public class GatewayHttpClientConfig {

    // destroyMethod = "close" for clean container shutdown.
    @Bean(destroyMethod = "close")
    public ExecutorService gatewayVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public RestClientCustomizer virtualThreadRestClientCustomizer(
            ExecutorService gatewayVirtualThreadExecutor,
            @Value("${app.proxy.connect-timeout:3s}") Duration connectTimeout,
            @Value("${app.proxy.read-timeout:120s}") Duration readTimeout) {

        var http11Client = HttpClient.newBuilder()
                .executor(gatewayVirtualThreadExecutor)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();

        var http2Client = HttpClient.newBuilder()
                .executor(gatewayVirtualThreadExecutor)
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(connectTimeout)
                .build();

        var http11Factory = new JdkClientHttpRequestFactory(http11Client, gatewayVirtualThreadExecutor);
        http11Factory.setReadTimeout(readTimeout);

        var http2Factory = new JdkClientHttpRequestFactory(http2Client, gatewayVirtualThreadExecutor);
        http2Factory.setReadTimeout(readTimeout);

        // Preserve HTTP/2 over TLS (ALPN) while avoiding wasted h2c upgrades on internal cleartext http:// hops
        ClientHttpRequestFactory protocolAwareFactory = (uri, httpMethod) ->
                "https".equalsIgnoreCase(uri.getScheme())
                        ? http2Factory.createRequest(uri, httpMethod)
                        : http11Factory.createRequest(uri, httpMethod);

        return restClientBuilder -> restClientBuilder.requestFactory(protocolAwareFactory);
    }

    /**
     * Required by Spring Cloud LoadBalancer's BlockingSupportConfiguration
     * (@ConditionalOnBean({DiscoveryClient.class, RestClient.class})) to activate
     * HealthCheckServiceInstanceListSupplier with fast probe timeouts.
     */
    @Bean
    public RestClient loadBalancerHealthCheckRestClient(ExecutorService gatewayVirtualThreadExecutor) {
        var healthCheckHttpClient = HttpClient.newBuilder()
                .executor(gatewayVirtualThreadExecutor)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1))
                .build();

        var healthCheckFactory = new JdkClientHttpRequestFactory(healthCheckHttpClient, gatewayVirtualThreadExecutor);
        healthCheckFactory.setReadTimeout(Duration.ofSeconds(2));

        return RestClient.builder()
                .requestFactory(healthCheckFactory)
                .build();
    }
}