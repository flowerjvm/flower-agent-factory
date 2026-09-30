package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerCancellationTransaction;
import io.github.flowerjvm.factory.application.work.WorkerRunRepository;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionDispatch;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.DeferredActionExecutor;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Creates a durable outbox intent; it never calls an external coding worker directly. */
public final class WorkerDispatchActionExecutor implements DeferredActionExecutor {
    private final WorkOrderRepository workOrders;
    private final WorkerRunRepository workerRuns;
    private final WorkerDispatchTransaction transaction;
    private final WorkerCancellationTransaction cancellationTransaction;
    private final Clock clock;

    public WorkerDispatchActionExecutor(
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            WorkerDispatchTransaction transaction,
            WorkerCancellationTransaction cancellationTransaction,
            Clock clock) {
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
        this.workerRuns = Objects.requireNonNull(workerRuns, "workerRuns");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.cancellationTransaction = Objects.requireNonNull(
                cancellationTransaction, "cancellationTransaction");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Compatibility constructor that remains fail-closed until a host wires durable cancellation. */
    @Deprecated(forRemoval = true)
    public WorkerDispatchActionExecutor(
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            WorkerDispatchTransaction transaction,
            Clock clock) {
        this(
                workOrders,
                workerRuns,
                transaction,
                (order, expected, cancelRequested, outbox) -> {
                    throw new IllegalStateException("durable Worker cancellation is not configured");
                },
                clock);
    }

    @Override
    public ActionDefinition definition() {
        return WorkerDispatchAction.definition();
    }

    @Override
    public ActionDispatch.Awaiting dispatchDeferred(ActionExecutionContext context) {
        WorkerDispatchInput input = WorkerDispatchInput.from(context.input());
        TenantId tenantId = new TenantId(context.executionContext().tenantId());
        var workOrder = workOrders.find(tenantId, input.workOrderId())
                .orElseThrow(() -> new IllegalStateException("WorkOrder disappeared before dispatch"));
        var workerRun = workerRuns.find(tenantId, input.workerRunId())
                .orElseThrow(() -> new IllegalStateException("WorkerRun disappeared before dispatch"));
        if (workerRun.version() != input.expectedWorkerRunVersion()) {
            throw new IllegalStateException("WorkerRun version changed before dispatch transaction");
        }
        String operationId = WorkerDispatchOperationIds.derive(workerRun);
        if (!operationId.equals(workerRun.operationId())) {
            throw new IllegalStateException("WorkerRun operation identity is not deterministic");
        }

        Instant now = clock.instant();
        DispatchOutbox outbox = new DispatchOutbox(
                new DispatchOutboxId(WorkerDispatchOperationIds.outboxId(operationId)),
                tenantId,
                WorkerOutboxOperations.DISPATCH,
                "WORKER_RUN",
                workerRun.workerRunId().value(),
                operationId,
                workOrder.inputArtifactManifestRef(),
                DispatchOutboxStatus.PENDING,
                now,
                0,
                Optional.empty(),
                0,
                now,
                now);
        var dispatching = workerRun.startDispatch(
                outbox.outboxId(),
                context.executionContext().runId(),
                WorkerDispatchOperationIds.hashAttemptToken(context.attemptToken()),
                now);
        if (!transaction.prepare(workOrder, workerRun, dispatching, outbox)) {
            throw new IllegalStateException("WorkerRun CAS lost while preparing dispatch outbox");
        }
        return ActionDispatch.awaiting(
                operationId,
                dispatching.deadlineAt(),
                Map.of(
                        "workOrderId", dispatching.workOrderId().value(),
                        "workerRunId", dispatching.workerRunId().value(),
                        "outboxId", outbox.outboxId().value()));
    }

    @Override
    public void cancel(ActionExecutionContext context, String operationId, String reason) {
        WorkerDispatchInput input = WorkerDispatchInput.from(context.input());
        TenantId tenantId = new TenantId(context.executionContext().tenantId());
        var order = workOrders.find(tenantId, input.workOrderId())
                .orElseThrow(() -> new IllegalStateException("WorkOrder disappeared before cancellation"));
        var current = workerRuns.find(tenantId, input.workerRunId())
                .orElseThrow(() -> new IllegalStateException("WorkerRun disappeared before cancellation"));
        if (!current.operationId().equals(operationId)
                || current.actionRunId().filter(context.executionContext().runId()::equals).isEmpty()
                || current.attemptTokenHash()
                        .filter(WorkerDispatchOperationIds.hashAttemptToken(context.attemptToken())::equals)
                        .isEmpty()) {
            throw new IllegalStateException("Worker cancellation owner tuple does not match the Action attempt");
        }
        Instant now = clock.instant();
        var cancelRequested = current.requestCancellation(now);
        DispatchOutbox outbox = new DispatchOutbox(
                new DispatchOutboxId(WorkerDispatchOperationIds.cancelOutboxId(operationId)),
                tenantId,
                WorkerOutboxOperations.CANCEL,
                "WORKER_RUN",
                current.workerRunId().value(),
                operationId,
                order.inputArtifactManifestRef(),
                DispatchOutboxStatus.PENDING,
                now,
                0,
                Optional.empty(),
                0,
                now,
                now);
        if (!cancellationTransaction.prepare(order, current, cancelRequested, outbox)) {
            throw new IllegalStateException("Worker cancellation CAS lost while preparing cancel outbox");
        }
    }
}
