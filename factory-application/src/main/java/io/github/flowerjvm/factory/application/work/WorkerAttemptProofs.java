package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Canonical HMAC proof used by callback and status-reconciliation completion envelopes. */
public final class WorkerAttemptProofs {
    private static final String VERSION = "factory.worker.attempt-proof.v1";

    private WorkerAttemptProofs() {}

    public static String create(
            String attemptToken, String eventId, String operationId, WorkerRunId workerRunId) {
        if (attemptToken == null || attemptToken.isBlank()) {
            throw new IllegalArgumentException("attemptToken must not be blank");
        }
        String material = String.join(
                "\n",
                VERSION,
                requireText(eventId, "eventId"),
                requireText(operationId, "operationId"),
                java.util.Objects.requireNonNull(workerRunId, "workerRunId").value());
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(attemptToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is required by the Java runtime", exception);
        }
    }

    public static boolean matches(
            String attemptToken,
            String eventId,
            String operationId,
            WorkerRunId workerRunId,
            String suppliedProof) {
        if (suppliedProof == null || !suppliedProof.matches("[0-9a-f]{64}")) {
            return false;
        }
        byte[] expected = HexFormat.of().parseHex(create(attemptToken, eventId, operationId, workerRunId));
        byte[] supplied = HexFormat.of().parseHex(suppliedProof);
        return MessageDigest.isEqual(expected, supplied);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
