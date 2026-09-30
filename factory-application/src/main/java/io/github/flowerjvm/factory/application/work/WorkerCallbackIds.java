package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic tokenless callback inbox identity. */
public final class WorkerCallbackIds {
    private static final String VERSION = "factory.worker.callback-inbox.v1";

    private WorkerCallbackIds() {}

    public static String derive(
            TenantId tenantId,
            String workerBindingId,
            String eventId,
            ContentHash payloadHash) {
        String material = String.join(
                "\n",
                VERSION,
                java.util.Objects.requireNonNull(tenantId, "tenantId").value(),
                requireText(workerBindingId, "workerBindingId"),
                requireText(eventId, "eventId"),
                java.util.Objects.requireNonNull(payloadHash, "payloadHash").sha256());
        try {
            return "callback-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
