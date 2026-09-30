package io.github.flowerjvm.factory.application.referenceassembly;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** One-way binding for the opaque Action attempt capability; plaintext is never persisted. */
public final class ReferenceAssemblyReleaseAttemptTokens {
    private ReferenceAssemblyReleaseAttemptTokens() {}

    public static String hash(String attemptToken) {
        if (attemptToken == null
                || attemptToken.isBlank()
                || attemptToken.length() > 512
                || !attemptToken.equals(attemptToken.trim())
                || attemptToken.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "attemptToken must be a bounded exact non-control capability");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(attemptToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
