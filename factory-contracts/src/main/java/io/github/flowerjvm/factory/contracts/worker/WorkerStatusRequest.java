package io.github.flowerjvm.factory.contracts.worker;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.util.Objects;

/** Exact owner tuple used to reconcile one deterministic external operation. */
public final class WorkerStatusRequest {
    private final TenantId tenantId;
    private final WorkOrderId workOrderId;
    private final WorkerRunId workerRunId;
    private final String operationId;
    private final WorkerAttemptToken attemptToken;

    public WorkerStatusRequest(
            TenantId tenantId,
            WorkOrderId workOrderId,
            WorkerRunId workerRunId,
            String operationId,
            WorkerAttemptToken attemptToken) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.workOrderId = Objects.requireNonNull(workOrderId, "workOrderId");
        this.workerRunId = Objects.requireNonNull(workerRunId, "workerRunId");
        this.operationId = requireText(operationId, "operationId", 256);
        this.attemptToken = Objects.requireNonNull(attemptToken, "attemptToken");
    }

    public TenantId tenantId() { return tenantId; }
    public WorkOrderId workOrderId() { return workOrderId; }
    public WorkerRunId workerRunId() { return workerRunId; }
    public String operationId() { return operationId; }
    public WorkerAttemptToken attemptToken() { return attemptToken; }

    @Override
    public String toString() {
        return "WorkerStatusRequest[tenantId=" + tenantId + ", workOrderId=" + workOrderId
                + ", workerRunId=" + workerRunId + ", operationId=" + operationId
                + ", attemptToken=[REDACTED]]";
    }

    static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
        }
        return value;
    }
}
