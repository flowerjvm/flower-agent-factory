package io.github.flowerjvm.factory.application.verification;

import io.github.flowerjvm.factory.application.action.VerificationRunInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic operation identity for one immutable domain verification attempt. */
public final class VerificationDispatchOperationIds {
    private VerificationDispatchOperationIds() {}

    public static String derive(VerificationRunInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        String material = "factory.verification.dispatch.v1\n"
                + input.verificationRunId().value() + "\n"
                + input.candidateId().value() + "\n"
                + input.expectedVerificationRunVersion();
        try {
            return "verification:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
