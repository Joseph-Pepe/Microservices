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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Mints and verifies short-lived (30s TTL) RFC 7519 HMAC-SHA256 (HS256) JWTs carrying sub, roles, jti, iss, iat, and exp. Fails fast at startup if the prod profile runs with the default/weak secret.
@Component
public class InternalAuthTokenService {

    public static final String HEADER_NAME = "X-Internal-Auth";
    public static final String LEGACY_HEADER_NAME = "X-Gateway-Validated";
    private static final String DEFAULT_DEV_SECRET = "dev-only-internal-auth-hmac-secret-key-change-in-prod-32bytes!";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final byte[] secretBytes;
    private final String issuer;
    private final long ttlSeconds;
    private final long maxAcceptedTtlSeconds;
    private final long clockSkewSeconds;
    private final JsonMapper jsonMapper;

    public InternalAuthTokenService(
            @Value("${app.internal-auth.secret:" + DEFAULT_DEV_SECRET + "}") String secret,
            @Value("${app.internal-auth.issuer:api-gateway}") String issuer,
            @Value("${app.internal-auth.ttl-seconds:30}") long ttlSeconds,
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
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("app.internal-auth.issuer must not be blank");
        }
        if (ttlSeconds <= 0 || ttlSeconds > 60) {
            throw new IllegalStateException("app.internal-auth.ttl-seconds must be between 1 and 60 seconds");
        }
        this.secretBytes = keyBytes;
        this.issuer = issuer;
        this.ttlSeconds = ttlSeconds;
        this.maxAcceptedTtlSeconds = Math.max(60L, ttlSeconds);
        this.clockSkewSeconds = Math.max(0L, clockSkewSeconds);
        this.jsonMapper = JsonMapper.builder().build();
    }

    public String mintToken(String subject, List<String> roles) {
        String resolvedSubject = (subject == null || subject.isBlank()) ? "anonymous" : subject;
        List<String> resolvedRoles = (roles == null) ? List.of() : List.copyOf(roles);
        long nowSeconds = Instant.now().getEpochSecond();

        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", resolvedSubject);
        payload.put("roles", resolvedRoles);
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("iss", issuer);
        payload.put("iat", nowSeconds);
        payload.put("exp", nowSeconds + ttlSeconds);

        String headerB64 = BASE64_URL_ENCODER.encodeToString(jsonMapper.writeValueAsString(header).getBytes(StandardCharsets.UTF_8));
        String payloadB64 = BASE64_URL_ENCODER.encodeToString(jsonMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
        String signingInput = headerB64 + "." + payloadB64;
        String signatureB64 = BASE64_URL_ENCODER.encodeToString(computeHmac(signingInput));
        return signingInput + "." + signatureB64;
    }

    public InternalClaims verifyToken(String token) {
        if (token == null || token.isBlank()) {
            throw new InternalAuthTokenException("Missing X-Internal-Auth token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new InternalAuthTokenException("Malformed X-Internal-Auth JWT structure");
        }

        byte[] headerBytes;
        byte[] payloadBytes;
        byte[] providedSig;
        try {
            headerBytes = BASE64_URL_DECODER.decode(parts[0]);
            payloadBytes = BASE64_URL_DECODER.decode(parts[1]);
            providedSig = BASE64_URL_DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new InternalAuthTokenException("Invalid Base64URL encoding in X-Internal-Auth token", e);
        }

        JsonNode headerNode;
        try {
            headerNode = jsonMapper.readTree(headerBytes);
        } catch (JacksonException e) {
            throw new InternalAuthTokenException("Invalid JWT header JSON", e);
        }

        JsonNode algNode = headerNode.get("alg");
        if (algNode == null || !algNode.isString() || !"HS256".equals(algNode.stringValue())) {
            throw new InternalAuthTokenException("Unsupported or missing JWT alg (expected HS256)");
        }

        // Constant-time HMAC verification BEFORE trusting any claims
        byte[] expectedSig = computeHmac(parts[0] + "." + parts[1]);
        if (!MessageDigest.isEqual(expectedSig, providedSig)) {
            throw new InternalAuthTokenException("Invalid X-Internal-Auth signature");
        }

        JsonNode payloadNode;
        try {
            payloadNode = jsonMapper.readTree(payloadBytes);
        } catch (JacksonException e) {
            throw new InternalAuthTokenException("Invalid JWT payload JSON", e);
        }

        String tokenIss = readRequiredString(payloadNode, "iss");
        if (!issuer.equals(tokenIss)) {
            throw new InternalAuthTokenException("Untrusted X-Internal-Auth issuer: " + tokenIss);
        }

        String tokenSub = readRequiredString(payloadNode, "sub");
        String tokenJti = readRequiredString(payloadNode, "jti");
        long iat = readRequiredLong(payloadNode, "iat");
        long exp = readRequiredLong(payloadNode, "exp");

        if (exp <= iat || (exp - iat) > maxAcceptedTtlSeconds) {
            throw new InternalAuthTokenException("Invalid X-Internal-Auth token TTL");
        }

        long now = Instant.now().getEpochSecond();
        if (iat > now + clockSkewSeconds) {
            throw new InternalAuthTokenException("X-Internal-Auth token issued in the future");
        }
        if (now >= exp + clockSkewSeconds) {
            throw new InternalAuthTokenException("X-Internal-Auth token has expired");
        }

        List<String> roles = readRoles(payloadNode.get("roles"));
        return new InternalClaims(tokenSub, roles, tokenJti, tokenIss, iat, exp);
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
            throw new InternalAuthTokenException("Missing or invalid claim: " + field);
        }
        return child.stringValue();
    }

    private static long readRequiredLong(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isIntegralNumber()) {
            throw new InternalAuthTokenException("Missing or invalid numeric claim: " + field);
        }
        return child.asLong();
    }

    private static List<String> readRoles(JsonNode rolesNode) {
        if (rolesNode == null || rolesNode.isNull()) {
            return List.of();
        }
        if (!rolesNode.isArray()) {
            throw new InternalAuthTokenException("Invalid roles claim: expected array");
        }
        List<String> roles = new ArrayList<>();
        for (JsonNode entry : rolesNode) {
            if (!entry.isString()) {
                throw new InternalAuthTokenException("Invalid role entry: expected string");
            }
            roles.add(entry.stringValue());
        }
        return List.copyOf(roles);
    }

    public record InternalClaims(
            String subject,
            List<String> roles,
            String jti,
            String issuer,
            long issuedAt,
            long expiresAt
    ) {
    }

    public static class InternalAuthTokenException extends RuntimeException {
        public InternalAuthTokenException(String message) {
            super(message);
        }

        public InternalAuthTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}