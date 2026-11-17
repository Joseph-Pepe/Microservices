package com.example.demo.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

// Independently verifies the HS256 signature (constant-time MessageDigest.isEqual before trusting claims), pins alg == "HS256", validates iss, sub, jti, iat/exp bounds, and fails closed if prod profile uses the default secret.
@Component
public class InternalAuthTokenVerifier {

    public static final String HEADER_NAME = "X-Internal-Auth";
    public static final String PRINCIPAL_ATTRIBUTE = "X-Internal-Principal";
    private static final String DEFAULT_DEV_SECRET = "dev-only-internal-auth-hmac-secret-key-change-in-prod-32bytes!";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final byte[] secretBytes;
    private final String expectedIssuer;
    private final long maxTtlSeconds;
    private final long clockSkewSeconds;
    private final JsonMapper jsonMapper;

    public InternalAuthTokenVerifier(
            @Value("${app.internal-auth.secret:" + DEFAULT_DEV_SECRET + "}") String secret,
            @Value("${app.internal-auth.expected-issuer:api-gateway}") String expectedIssuer,
            @Value("${app.internal-auth.max-ttl-seconds:60}") long maxTtlSeconds,
            @Value("${app.internal-auth.clock-skew-seconds:5}") long clockSkewSeconds,
            Environment environment) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.internal-auth.secret must not be blank");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("app.internal-auth.secret must be at least 32 bytes (256 bits)");
        }
        if (environment != null && environment.acceptsProfiles(Profiles.of("prod")) && DEFAULT_DEV_SECRET.equals(secret)) {
            throw new IllegalStateException("CRITICAL: Production profile cannot boot with the default dev INTERNAL_AUTH_SECRET.");
        }
        if (expectedIssuer == null || expectedIssuer.isBlank()) {
            throw new IllegalStateException("app.internal-auth.expected-issuer must not be blank");
        }
        if (maxTtlSeconds <= 0) {
            throw new IllegalStateException("app.internal-auth.max-ttl-seconds must be positive");
        }
        this.secretBytes = keyBytes;
        this.expectedIssuer = expectedIssuer;
        this.maxTtlSeconds = maxTtlSeconds;
        this.clockSkewSeconds = Math.max(0L, clockSkewSeconds);
        this.jsonMapper = JsonMapper.builder().build();
    }

    public InternalPrincipal verify(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidInternalAuthException("Missing X-Internal-Auth token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new InvalidInternalAuthException("Malformed X-Internal-Auth JWT structure");
        }

        byte[] headerBytes;
        byte[] payloadBytes;
        byte[] providedSig;
        try {
            headerBytes = BASE64_URL_DECODER.decode(parts[0]);
            payloadBytes = BASE64_URL_DECODER.decode(parts[1]);
            providedSig = BASE64_URL_DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new InvalidInternalAuthException("Invalid Base64URL encoding in X-Internal-Auth token", e);
        }

        JsonNode headerNode;
        try {
            headerNode = jsonMapper.readTree(headerBytes);
        } catch (JacksonException e) {
            throw new InvalidInternalAuthException("Invalid JWT header JSON", e);
        }

        JsonNode algNode = headerNode.get("alg");
        if (algNode == null || !algNode.isString() || !"HS256".equals(algNode.stringValue())) {
            throw new InvalidInternalAuthException("Unsupported or missing JWT alg (expected HS256)");
        }

        // Constant-time HMAC verification BEFORE trusting any claims
        byte[] expectedSig = computeHmac(parts[0] + "." + parts[1]);
        if (!MessageDigest.isEqual(expectedSig, providedSig)) {
            throw new InvalidInternalAuthException("Invalid X-Internal-Auth signature");
        }

        JsonNode payloadNode;
        try {
            payloadNode = jsonMapper.readTree(payloadBytes);
        } catch (JacksonException e) {
            throw new InvalidInternalAuthException("Invalid JWT payload JSON", e);
        }

        String issuer = readRequiredString(payloadNode, "iss");
        if (!expectedIssuer.equals(issuer)) {
            throw new InvalidInternalAuthException("Untrusted X-Internal-Auth issuer: " + issuer);
        }

        String subject = readRequiredString(payloadNode, "sub");
        String jti = readRequiredString(payloadNode, "jti");
        long iat = readRequiredLong(payloadNode, "iat");
        long exp = readRequiredLong(payloadNode, "exp");

        if (exp <= iat || (exp - iat) > maxTtlSeconds) {
            throw new InvalidInternalAuthException("Invalid X-Internal-Auth token lifetime");
        }

        long now = Instant.now().getEpochSecond();
        if (iat > now + clockSkewSeconds) {
            throw new InvalidInternalAuthException("X-Internal-Auth token issued in the future");
        }
        if (now >= exp + clockSkewSeconds) {
            throw new InvalidInternalAuthException("X-Internal-Auth token has expired");
        }

        List<String> roles = readRoles(payloadNode.get("roles"));
        return new InternalPrincipal(subject, roles, jti, issuer, iat, exp);
    }

    private byte[] computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }

    private static String readRequiredString(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isString() || child.stringValue().isBlank()) {
            throw new InvalidInternalAuthException("Missing or invalid claim: " + field);
        }
        return child.stringValue();
    }

    private static long readRequiredLong(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isIntegralNumber()) {
            throw new InvalidInternalAuthException("Missing or invalid numeric claim: " + field);
        }
        return child.asLong();
    }

    private static List<String> readRoles(JsonNode rolesNode) {
        if (rolesNode == null || rolesNode.isNull()) {
            return List.of();
        }
        if (!rolesNode.isArray()) {
            throw new InvalidInternalAuthException("Invalid roles claim: expected array");
        }
        List<String> roles = new ArrayList<>();
        for (JsonNode entry : rolesNode) {
            if (!entry.isString()) {
                throw new InvalidInternalAuthException("Invalid role entry: expected string");
            }
            roles.add(entry.stringValue());
        }
        return List.copyOf(roles);
    }

    public record InternalPrincipal(
            String subject,
            List<String> roles,
            String jti,
            String issuer,
            long issuedAt,
            long expiresAt
    ) {
    }

    public static class InvalidInternalAuthException extends RuntimeException {
        public InvalidInternalAuthException(String message) {
            super(message);
        }

        public InvalidInternalAuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}