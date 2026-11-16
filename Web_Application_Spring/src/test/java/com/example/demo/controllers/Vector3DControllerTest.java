package com.example.demo.controllers;

import com.example.demo.model.Vector3D;
import com.example.demo.security.GatewaySecurityFilter;
import com.example.demo.security.InternalAuthTokenVerifier;
import com.example.demo.security.InternalAuthTokenVerifier.InternalPrincipal;
import com.example.demo.services.Vector3DService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Tells Spring to ONLY load the web layer and this specific controller
@WebMvcTest(controllers = Vector3DController.class)

// Explicitly tell Spring Boot to load your GatewaySecurityFilter, controller and service classes into this test sandbox so it intercepts traffic.
@ContextConfiguration(classes = {
        Vector3DController.class,
        Vector3DService.class,
        GatewaySecurityFilter.class,
        InternalAuthTokenVerifier.class
})
// Tests valid HS256 X-Internal-Auth tokens, missing headers (403), legacy X-Gateway-Validated: true alone (403), forged signatures (401), expired tokens (401), alg: none downgrade (401), and wrong issuer (401).
// Simulates HTTP traffic without actually starting a real web server.
class Vector3DControllerTest {

    private static final String TEST_SECRET = "dev-only-internal-auth-hmac-secret-key-change-in-prod-32bytes!";
    private static final String REQUEST_JSON = """
            {
              "v1": { "x": 1.0, "y": 2.0, "z": 3.0 },
              "v2": { "x": 4.0, "y": 5.0, "z": 6.0 }
            }
            """; // A sample JSON payload to send in the test

    @Autowired
    private MockMvc mockMvc; // Simulates HTTP requests

    @MockitoBean
    private Vector3DService vectorService; // Creates a fake version of our service

    @Test
    void testAddVectorsEndpoint_withValidSignedInternalAuth_shouldReturn200() throws Exception {
        // 1. Arrange: Tell the fake service what to return when it is called
        Mockito.when(vectorService.addVectors(any())).thenReturn(new Vector3D(5.0, 7.0, 9.0));

        long now = Instant.now().getEpochSecond();
        String validToken = signJwt(
                Map.of("alg", "HS256", "typ", "JWT"),
                Map.of(
                        "sub", "alice",
                        "roles", List.of("ROLE_ADMIN", "ROLE_USER"),
                        "jti", UUID.randomUUID().toString(),
                        "iss", "api-gateway",
                        "iat", now,
                        "exp", now + 30
                ),
                TEST_SECRET
        );

        // 2. Assert: Send the request and check the response (i.e., make sure the endpoint returns the correct JSON and status codes)
        var result = mockMvc.perform(post("/api/vectors/addVectors")
                        .header(InternalAuthTokenVerifier.HEADER_NAME, validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.x").value(5.0))
                .andExpect(jsonPath("$.y").value(7.0))
                .andExpect(jsonPath("$.z").value(9.0))
                .andReturn();

        InternalPrincipal principal = (InternalPrincipal) result.getRequest()
                .getAttribute(InternalAuthTokenVerifier.PRINCIPAL_ATTRIBUTE);
        assertThat(principal).isNotNull();
        assertThat(principal.subject()).isEqualTo("alice");
        assertThat(principal.roles()).containsExactly("ROLE_ADMIN", "ROLE_USER");
        assertThat(principal.issuer()).isEqualTo("api-gateway");
    }

    @Test
    void testAddVectorsEndpoint_withoutInternalAuthHeader_shouldReturn403Forbidden() throws Exception {
        mockMvc.perform(post("/api/vectors/addVectors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    void testAddVectorsEndpoint_withOnlyLegacyGatewayValidatedHeader_shouldReturn403Forbidden() throws Exception {
        // Assert: Send the exact same request, but FORGET the header
        // Because the header is missing, the GatewaySecurityFilter should intercept it and throw a 403.
        mockMvc.perform(post("/api/vectors/addVectors")
                        .header("X-Gateway-Validated", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().isForbidden()) // 3. EXPECT A 403 REJECTION
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    void testAddVectorsEndpoint_withForgedInternalAuthSignature_shouldReturn401Unauthorized() throws Exception {
        long now = Instant.now().getEpochSecond();
        String forgedToken = signJwt(
                Map.of("alg", "HS256", "typ", "JWT"),
                Map.of(
                        "sub", "attacker",
                        "roles", List.of("ROLE_ADMIN"),
                        "jti", UUID.randomUUID().toString(),
                        "iss", "api-gateway",
                        "iat", now,
                        "exp", now + 30
                ),
                "wrong-attacker-signing-secret-at-least-32-bytes!"
        );

        mockMvc.perform(post("/api/vectors/addVectors")
                        .header(InternalAuthTokenVerifier.HEADER_NAME, forgedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void testAddVectorsEndpoint_withExpiredInternalAuthToken_shouldReturn401Unauthorized() throws Exception {
        long now = Instant.now().getEpochSecond();
        String expiredToken = signJwt(
                Map.of("alg", "HS256", "typ", "JWT"),
                Map.of(
                        "sub", "alice",
                        "roles", List.of("ROLE_USER"),
                        "jti", UUID.randomUUID().toString(),
                        "iss", "api-gateway",
                        "iat", now - 60,
                        "exp", now - 30
                ),
                TEST_SECRET
        );

        mockMvc.perform(post("/api/vectors/addVectors")
                        .header(InternalAuthTokenVerifier.HEADER_NAME, expiredToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void testAddVectorsEndpoint_withAlgNoneOrWrongIssuer_shouldReturn401Unauthorized() throws Exception {
        long now = Instant.now().getEpochSecond();
        String algNoneToken = signJwt(
                Map.of("alg", "none", "typ", "JWT"),
                Map.of(
                        "sub", "alice",
                        "roles", List.of("ROLE_ADMIN"),
                        "jti", UUID.randomUUID().toString(),
                        "iss", "api-gateway",
                        "iat", now,
                        "exp", now + 30
                ),
                TEST_SECRET
        );

        mockMvc.perform(post("/api/vectors/addVectors")
                        .header(InternalAuthTokenVerifier.HEADER_NAME, algNoneToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().isUnauthorized());

        String wrongIssuerToken = signJwt(
                Map.of("alg", "HS256", "typ", "JWT"),
                Map.of(
                        "sub", "alice",
                        "roles", List.of("ROLE_USER"),
                        "jti", UUID.randomUUID().toString(),
                        "iss", "evil-gateway",
                        "iat", now,
                        "exp", now + 30
                ),
                TEST_SECRET
        );

        mockMvc.perform(post("/api/vectors/addVectors")
                        .header(InternalAuthTokenVerifier.HEADER_NAME, wrongIssuerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().isUnauthorized());
    }

    private static String signJwt(Map<String, Object> header, Map<String, Object> payload, String secret) throws Exception {
        var mapper = JsonMapper.builder().build();
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String h = encoder.encodeToString(mapper.writeValueAsString(header).getBytes(StandardCharsets.UTF_8));
        String p = encoder.encodeToString(mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
        String signingInput = h + "." + p;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = encoder.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        return signingInput + "." + sig;
    }
}