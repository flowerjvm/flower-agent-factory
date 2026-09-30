package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** One stable key per logical Worker attempt; transport redelivery reuses it. */
public final class WorkerDispatchIdempotencyKeys {
    private static final String VERSION = "factory.worker.dispatch.idempotency.v1";

    private WorkerDispatchIdempotencyKeys() {}

    public static String derive(WorkOrder workOrder, WorkerRunRecord workerRun) {
        return derive(
                workOrder.tenantId(),
                workOrder.workOrderId(),
                workOrder.logicalIdempotencyKey(),
                workerRun.workerRunId(),
                workerRun.attemptNo());
    }

    public static String derive(
            TenantId tenantId,
            WorkOrderId workOrderId,
            String logicalWorkOrderKey,
            WorkerRunId workerRunId,
            int attemptNo) {
        if (tenantId == null || workOrderId == null || workerRunId == null) {
            throw new IllegalArgumentException("tenantId, workOrderId and workerRunId are required");
        }
        if (logicalWorkOrderKey == null || logicalWorkOrderKey.isBlank()) {
            throw new IllegalArgumentException("logicalWorkOrderKey must not be blank");
        }
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be at least one");
        }
        String material = String.join(
                "\n",
                VERSION,
                tenantId.value(),
                workOrderId.value(),
                logicalWorkOrderKey,
                workerRunId.value(),
                Integer.toString(attemptNo));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "worker-dispatch-attempt-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
