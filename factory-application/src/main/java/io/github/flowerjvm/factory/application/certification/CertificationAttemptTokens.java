package io.github.flowerjvm.factory.application.certification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** One-way Action attempt-token binding; the durable Certification intent never stores the capability. */
public final class CertificationAttemptTokens {
    private CertificationAttemptTokens() {}

    public static String hash(String attemptToken) {
        if (attemptToken == null || attemptToken.isBlank()) {
            throw new IllegalArgumentException("attemptToken must not be blank");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(attemptToken.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
