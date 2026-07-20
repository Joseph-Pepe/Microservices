package com.example.demo; 

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityAndRateLimitTests {

    @Autowired
    private WebTestClient webClient; 

    @Test
    void testHackerAttempt_shouldReturn401Unauthorized() {
        webClient.post().uri("/api/vectors/calculateMagnitude")
                .exchange() 
                .expectStatus().isUnauthorized(); 
    }

    @Test
    void testSpammingServer_shouldTriggerRateLimiter429() {
        WebTestClient patientClient = webClient.mutate()
                .responseTimeout(Duration.ofSeconds(10))
                .build();

        // 1. We use a loop to rapidly fire 10 valid requests.
        // The bouncer's sliding window will let all 10 of these through.
        for (int i = 0; i < 10; i++) {
            patientClient.post().uri("/api/vectors/calculateMagnitude")
                    .headers(headers -> headers.setBasicAuth("admin", "vector-secret-123"))
                    .exchange(); 
        }

        // 2. The Trap: Fire the 11th request instantly.
        // The queue now has 10 timestamps from the last 30 seconds, so the bouncer MUST block this one.
        patientClient.post().uri("/api/vectors/calculateMagnitude")
                .headers(headers -> headers.setBasicAuth("admin", "vector-secret-123"))
                .exchange() 
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS) 
                .expectBody() 
                .jsonPath("$.error").isEqualTo("Too Many Requests"); 
    }

//     @Test
//     void testSpammingServer_shouldTriggerRateLimiter429() {
//         // Give the test robot 10 seconds of patience to account for the server's cold start!
//         WebTestClient patientClient = webClient.mutate()
//                 .responseTimeout(Duration.ofSeconds(10))
//                 .build();

//         // 1. Send Request #1 (Warms up the server and spends the first token)
//         patientClient.post().uri("/api/vectors/calculateMagnitude")
//                 .headers(headers -> headers.setBasicAuth("admin", "vector-secret-123"))
//                 .exchange(); // We don't check the status here, because Port 8081 isn't running in the test environment!

//         // 2. Send Request #2 immediately after (Fired in milliseconds!)
//         patientClient.post().uri("/api/vectors/calculateMagnitude")
//                 .headers(headers -> headers.setBasicAuth("admin", "vector-secret-123"))
//                 .exchange() 
//                 .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS) 
//                 .expectBody() 
//                 .jsonPath("$.error").isEqualTo("Too Many Requests"); 
//     }
}