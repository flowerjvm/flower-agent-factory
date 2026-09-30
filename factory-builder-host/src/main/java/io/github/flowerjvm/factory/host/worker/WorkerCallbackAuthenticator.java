package io.github.flowerjvm.factory.host.worker;

import io.github.flowerjvm.factory.application.work.TrustedWorkerCallbackContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Validates the outer transport envelope before any tenant-scoped lookup or artifact write. */
public final class WorkerCallbackAuthenticator {
    public static final String SIGNATURE_VERSION = "factory.worker.callback.v1";
    private static final byte[] DUMMY_SECRET = new byte[32];

    private final WorkerCallbackCredentialRegistry credentials;
    private final Clock clock;
    private final Duration allowedClockSkew;

    public WorkerCallbackAuthenticator(
            WorkerCallbackCredentialRegistry credentials,
            Clock clock,
            Duration allowedClockSkew) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.allowedClockSkew = Objects.requireNonNull(allowedClockSkew, "allowedClockSkew");
        if (allowedClockSkew.isNegative() || allowedClockSkew.isZero()
                || allowedClockSkew.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("allowedClockSkew must be within 1ns..15m");
        }
    }

    public Optional<AuthenticatedCallback> authenticate(
            String keyId,
            String timestampText,
            String nonce,
            String signature,
            byte[] body) {
        Objects.requireNonNull(body, "body");
        Optional<WorkerCallbackCredential> candidate = validKeyId(keyId)
                ? credentials.find(keyId)
                : Optional.empty();
        Optional<Instant> timestamp = parseTimestamp(timestampText);
        boolean fieldsValid = candidate.isPresent()
                && timestamp.isPresent()
                && validNonce(nonce)
                && signature != null
                && signature.matches("[0-9a-f]{64}");

        String bodyHash = sha256(body);
        String material = SIGNATURE_VERSION + '\n'
                + safe(keyId) + '\n'
                + safe(timestampText) + '\n'
                + safe(nonce) + '\n'
                + bodyHash;
        byte[] secret = candidate.map(WorkerCallbackCredential::secretCopy)
                .orElseGet(() -> DUMMY_SECRET.clone());
        byte[] expected;
        try {
            expected = hmac(secret, material);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        byte[] presented = decodeHexOrZeros(signature);
        boolean signatureMatches = MessageDigest.isEqual(expected, presented);
        Arrays.fill(expected, (byte) 0);
        Arrays.fill(presented, (byte) 0);
        if (!fieldsValid || !signatureMatches || !withinWindow(timestamp.orElse(Instant.EPOCH))) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedCallback(
                candidate.orElseThrow().trustedContext(), bodyHash));
    }

    /** Returns only credential-derived routing metadata; it never exposes key material. */
    public Optional<TrustedWorkerCallbackContext> configuredContext(String keyId) {
        if (!validKeyId(keyId)) {
            return Optional.empty();
        }
        return credentials.find(keyId).map(WorkerCallbackCredential::trustedContext);
    }

    public static String signForTesting(
            byte[] secret,
            String keyId,
            String timestamp,
            String nonce,
            byte[] body) {
        String material = SIGNATURE_VERSION + '\n' + keyId + '\n' + timestamp + '\n' + nonce + '\n'
                + sha256(body);
        byte[] value = hmac(secret.clone(), material);
        try {
            return HexFormat.of().formatHex(value);
        } finally {
            Arrays.fill(value, (byte) 0);
        }
    }

    private boolean withinWindow(Instant timestamp) {
        Duration distance = Duration.between(timestamp, clock.instant()).abs();
        return distance.compareTo(allowedClockSkew) <= 0;
    }

    private static Optional<Instant> parseTimestamp(String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.ofEpochSecond(Long.parseLong(value)));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static boolean validKeyId(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,128}");
    }

    private static boolean validNonce(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{16,128}");
    }

    private static String safe(String value) {
        if (value == null || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
            return "";
        }
        return value;
    }

    private static byte[] decodeHexOrZeros(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            return new byte[32];
        }
        return HexFormat.of().parseHex(value);
    }

    private static byte[] hmac(byte[] secret, String material) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is unavailable", unavailable);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    public record AuthenticatedCallback(
            TrustedWorkerCallbackContext trustedContext,
            String bodySha256) {
        public AuthenticatedCallback {
            Objects.requireNonNull(trustedContext, "trustedContext");
            if (bodySha256 == null || !bodySha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("bodySha256 must be lowercase SHA-256");
            }
        }
    }
}
