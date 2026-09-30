package io.github.flowerjvm.factory.infrastructure;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.CodingWorker;
import io.github.flowerjvm.factory.contracts.worker.WorkerDispatchRequest;
import io.github.flowerjvm.factory.contracts.worker.WorkerCancelRequest;
import io.github.flowerjvm.factory.contracts.worker.WorkerCancelResult;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunSnapshot;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.factory.contracts.worker.WorkerSubmission;
import io.github.flowerjvm.factory.contracts.worker.WorkerStatusObservation;
import io.github.flowerjvm.factory.contracts.worker.WorkerStatusRequest;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class DeterministicFakeCodingWorker implements CodingWorker {
    private final WorkerCapabilities capabilities;
    private final Map<OperationKey, WorkerRunId> runsByOperation = new ConcurrentHashMap<>();
    private final Map<WorkerRunKey, WorkerRunSnapshot> snapshots = new ConcurrentHashMap<>();
    private final AtomicInteger dispatchCount = new AtomicInteger();

    DeterministicFakeCodingWorker(WorkerCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    @Override
    public WorkerCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public WorkerSubmission submit(WorkerDispatchRequest request) {
        var order = request.workOrder();
        if (!capabilities.supportsAll(order.requiredCapabilities())) {
            throw new IllegalArgumentException("WORK_ORDER_CAPABILITY_MISMATCH");
        }
        var operationKey = new OperationKey(order.tenantId(), request.operationId());
        var workerRunId = runsByOperation.computeIfAbsent(operationKey, ignored -> {
            dispatchCount.incrementAndGet();
            snapshots.put(
                    new WorkerRunKey(order.tenantId(), request.workerRunId()),
                    snapshot(
                            order.tenantId(),
                            order.workOrderId(),
                            request.workerRunId(),
                            request.operationId(),
                            WorkerRunStatus.WAITING_EXTERNAL,
                            Optional.empty()));
            return request.workerRunId();
        });
        if (!workerRunId.equals(request.workerRunId())) {
            throw new IllegalArgumentException("operationId is already bound to another WorkerRun");
        }
        var current = status(order.tenantId(), workerRunId);
        if (!current.workOrderId().equals(order.workOrderId())) {
            throw new IllegalArgumentException("operationId is already bound to another WorkOrder");
        }
        return new WorkerSubmission(workerRunId, request.operationId(), current.status());
    }

    @Override
    public WorkerStatusObservation status(WorkerStatusRequest request) {
        var snapshot = status(request.tenantId(), request.workerRunId());
        if (!snapshot.workOrderId().equals(request.workOrderId())
                || !snapshot.operationId().equals(request.operationId())) {
            return WorkerStatusObservation.unknown("CODING_WORKER_OWNER_MISMATCH");
        }
        return WorkerStatusObservation.found(snapshot);
    }

    public WorkerRunSnapshot status(TenantId tenantId, WorkerRunId workerRunId) {
        var snapshot = snapshots.get(new WorkerRunKey(tenantId, workerRunId));
        if (snapshot == null) {
            throw new NoSuchElementException("worker run not found");
        }
        return snapshot;
    }

    @Override
    public WorkerCancelResult cancel(WorkerCancelRequest request) {
        WorkerRunSnapshot snapshot = status(request.tenantId(), request.workerRunId());
        if (!snapshot.workOrderId().equals(request.workOrderId())
                || !snapshot.operationId().equals(request.operationId())) {
            throw new IllegalArgumentException("CODING_WORKER_OWNER_MISMATCH");
        }
        return cancel(request.tenantId(), request.workerRunId(), request.reasonCode());
    }

    public WorkerCancelResult cancel(TenantId tenantId, WorkerRunId workerRunId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("cancel reason must not be blank");
        }
        var key = new WorkerRunKey(tenantId, workerRunId);
        var cancelled = snapshots.computeIfPresent(
                key,
                (ignored, current) -> isTerminal(current.status())
                        ? current
                        : snapshot(
                                current.tenantId(),
                                current.workOrderId(),
                                current.workerRunId(),
                                current.operationId(),
                                WorkerRunStatus.CANCELLED,
                                current.resultArtifact()));
        if (cancelled == null) {
            throw new NoSuchElementException("worker run not found");
        }
        String stableCode =
                cancelled.status() == WorkerRunStatus.CANCELLED ? "WORKER_CANCELLED" : "WORKER_ALREADY_TERMINAL";
        return new WorkerCancelResult(workerRunId, cancelled.status(), stableCode);
    }

    void complete(TenantId tenantId, WorkerRunId workerRunId, ArtifactReference resultArtifact) {
        var key = new WorkerRunKey(tenantId, workerRunId);
        var completed = snapshots.computeIfPresent(
                key,
                (ignored, current) -> isTerminal(current.status())
                        ? current
                        : snapshot(
                                current.tenantId(),
                                current.workOrderId(),
                                current.workerRunId(),
                                current.operationId(),
                                WorkerRunStatus.SUCCEEDED,
                                Optional.of(resultArtifact)));
        if (completed == null) {
            throw new NoSuchElementException("worker run not found");
        }
    }

    int dispatchCount() {
        return dispatchCount.get();
    }

    private static boolean isTerminal(WorkerRunStatus status) {
        return status == WorkerRunStatus.SUCCEEDED
                || status == WorkerRunStatus.FAILED
                || status == WorkerRunStatus.CANCELLED
                || status == WorkerRunStatus.TIMED_OUT;
    }

    private static WorkerRunSnapshot snapshot(
            TenantId tenantId,
            WorkOrderId workOrderId,
            WorkerRunId workerRunId,
            String operationId,
            WorkerRunStatus status,
            Optional<ArtifactReference> resultArtifact) {
        return new WorkerRunSnapshot(
                tenantId, workOrderId, workerRunId, operationId, status, resultArtifact, Optional.empty());
    }

    private record OperationKey(TenantId tenantId, String operationId) {
    }

    private record WorkerRunKey(TenantId tenantId, WorkerRunId workerRunId) {
    }
}
