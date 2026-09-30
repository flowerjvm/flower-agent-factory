package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Factory domain operation identities; deliberately independent of Action attempt tokens. */
public final class WorkerDispatchOperationIds {
    private static final String VERSION = "factory.worker.dispatch.operation.v2";

    private WorkerDispatchOperationIds() {}

    public static String derive(WorkerRunRecord workerRun) {
        return derive(
                workerRun.tenantId(),
                workerRun.workOrderId(),
                workerRun.workerRunId(),
                workerRun.attemptNo(),
                workerRun.workerBindingId());
    }

    public static String derive(
            TenantId tenantId,
            WorkOrderId workOrderId,
            WorkerRunId workerRunId,
            int attemptNo,
            String workerBindingId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (workOrderId == null) {
            throw new IllegalArgumentException("workOrderId must not be null");
        }
        if (workerRunId == null) {
            throw new IllegalArgumentException("workerRunId must not be null");
        }
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be at least one");
        }
        if (workerBindingId == null || workerBindingId.isBlank()) {
            throw new IllegalArgumentException("workerBindingId must not be blank");
        }
        String material = String.join(
                "\n",
                VERSION,
                tenantId.value(),
                workOrderId.value(),
                workerRunId.value(),
                Integer.toString(attemptNo),
                workerBindingId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "worker-dispatch-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    /** Tenant-less operation identities are unsafe for a globally shared Worker journal. */
    @Deprecated(forRemoval = true)
    public static String derive(WorkerRunId workerRunId, int attemptNo, String workerBindingId) {
        throw new IllegalArgumentException(
                "tenantId and workOrderId are required for a Worker operation identity");
    }

    public static String outboxId(String operationId) {
        return outboxId("dispatch", operationId);
    }

    public static String cancelOutboxId(String operationId) {
        return outboxId("cancel", operationId);
    }

    private static String outboxId(String operationType, String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((operationType + "\n" + operationId).getBytes(StandardCharsets.UTF_8));
            return operationType + "-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    /** Stores callback authentication material only as a SHA-256 hash in the WorkerRun ledger. */
    public static ContentHash hashAttemptToken(String attemptToken) {
        if (attemptToken == null || attemptToken.isBlank()) {
            throw new IllegalArgumentException("attemptToken must not be blank");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(attemptToken.getBytes(StandardCharsets.UTF_8));
            return new ContentHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
