package io.github.flowerjvm.factory.contracts.worker;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.util.Objects;

/** Exact owner tuple for one idempotent cooperative cancellation request. */
public final class WorkerCancelRequest {
    private final TenantId tenantId;
    private final WorkOrderId workOrderId;
    private final WorkerRunId workerRunId;
    private final String operationId;
    private final WorkerAttemptToken attemptToken;
    private final String reasonCode;

    public WorkerCancelRequest(
            TenantId tenantId,
            WorkOrderId workOrderId,
            WorkerRunId workerRunId,
            String operationId,
            WorkerAttemptToken attemptToken,
            String reasonCode) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.workOrderId = Objects.requireNonNull(workOrderId, "workOrderId");
        this.workerRunId = Objects.requireNonNull(workerRunId, "workerRunId");
        this.operationId = WorkerStatusRequest.requireText(operationId, "operationId", 256);
        this.attemptToken = Objects.requireNonNull(attemptToken, "attemptToken");
        this.reasonCode = WorkerStatusRequest.requireText(reasonCode, "reasonCode", 128);
        if (!reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("reasonCode must be a stable uppercase code");
        }
    }

    public TenantId tenantId() { return tenantId; }
    public WorkOrderId workOrderId() { return workOrderId; }
    public WorkerRunId workerRunId() { return workerRunId; }
    public String operationId() { return operationId; }
    public WorkerAttemptToken attemptToken() { return attemptToken; }
    public String reasonCode() { return reasonCode; }

    @Override
    public String toString() {
        return "WorkerCancelRequest[tenantId=" + tenantId + ", workOrderId=" + workOrderId
                + ", workerRunId=" + workerRunId + ", operationId=" + operationId
                + ", attemptToken=[REDACTED], reasonCode=" + reasonCode + "]";
    }
}
