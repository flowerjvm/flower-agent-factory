package io.github.flowerjvm.factory.contracts.worker;

import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.util.Objects;

/**
 * Dispatches an already-created durable WorkerRun instead of asking an adapter to invent domain
 * identity.
 */
public final class WorkerDispatchRequest {
    private final WorkOrder workOrder;
    private final WorkerRunId workerRunId;
    private final String operationId;
    private final WorkerAttemptToken attemptToken;

    public WorkerDispatchRequest(
            WorkOrder workOrder,
            WorkerRunId workerRunId,
            String operationId,
            WorkerAttemptToken attemptToken) {
        this.workOrder = Objects.requireNonNull(workOrder, "workOrder");
        this.workerRunId = Objects.requireNonNull(workerRunId, "workerRunId");
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        this.operationId = operationId;
        this.attemptToken = Objects.requireNonNull(attemptToken, "attemptToken");
    }

    public WorkOrder workOrder() {
        return workOrder;
    }

    public WorkerRunId workerRunId() {
        return workerRunId;
    }

    public String operationId() {
        return operationId;
    }

    public WorkerAttemptToken attemptToken() {
        return attemptToken;
    }

    @Override
    public String toString() {
        return "WorkerDispatchRequest[workOrderId=" + workOrder.workOrderId()
                + ", workerRunId=" + workerRunId
                + ", operationId=" + operationId
                + ", attemptToken=[REDACTED]]";
    }
}
