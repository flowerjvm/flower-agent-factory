package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchInput;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxRepository;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Re-stages the durable Worker cancel intent if the Action Runtime persisted CANCELLED after the
 * deferred cancel hook failed before committing its domain transaction.
 */
public final class WorkerCancellationIntentRecovery {
    public static final int DEFAULT_SCAN_LIMIT = 64;

    private final WorkOrderRepository workOrders;
    private final WorkerRunRepository workerRuns;
    private final DispatchOutboxRepository outboxes;
    private final RunStore runStore;
    private final WorkerCancellationTransaction transaction;
    private final Clock clock;
    private final int scanLimit;

    public WorkerCancellationIntentRecovery(
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            DispatchOutboxRepository outboxes,
            RunStore runStore,
            WorkerCancellationTransaction transaction,
            Clock clock) {
        this(
                workOrders, workerRuns, outboxes, runStore, transaction, clock,
                DEFAULT_SCAN_LIMIT);
    }

    public WorkerCancellationIntentRecovery(
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            DispatchOutboxRepository outboxes,
            RunStore runStore,
            WorkerCancellationTransaction transaction,
            Clock clock,
            int scanLimit) {
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
        this.workerRuns = Objects.requireNonNull(workerRuns, "workerRuns");
        this.outboxes = Objects.requireNonNull(outboxes, "outboxes");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (scanLimit < 1 || scanLimit > 1_000) {
            throw new IllegalArgumentException("scanLimit must be between 1 and 1000");
        }
        this.scanLimit = scanLimit;
    }

    /** Bounded startup/pump scan; it never calls the external Worker. */
    public boolean tickOnce() {
        Instant now = clock.instant();
        for (WorkerRunRecord run : workerRuns.findCancellationRecoveryCandidates(now, scanLimit)) {
            if (needsRecovery(run) && ensure(run.tenantId(), run.workerRunId())) {
                return true;
            }
        }
        return false;
    }

    /** Ensures the deterministic cancel outbox for one exact Action-cancelled Worker owner. */
    public boolean ensure(TenantId tenantId, WorkerRunId workerRunId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(workerRunId, "workerRunId");
        WorkerRunRecord current = workerRuns.find(tenantId, workerRunId).orElse(null);
        if (!needsRecovery(current)) {
            return alreadyStaged(current);
        }
        WorkOrder order = workOrders.find(tenantId, current.workOrderId()).orElse(null);
        ActionRun action = current.actionRunId().flatMap(runStore::find).orElse(null);
        if (!exactCancelledOwner(current, order, action)) {
            return false;
        }
        Instant now = clock.instant();
        if (now.isBefore(current.updatedAt())) {
            return false;
        }
        DispatchOutboxId outboxId = new DispatchOutboxId(
                WorkerDispatchOperationIds.cancelOutboxId(current.operationId()));
        Optional<DispatchOutbox> existing = outboxes.findByOperation(
                tenantId, WorkerOutboxOperations.CANCEL, current.operationId());
        if (existing.isPresent()) {
            return false;
        }
        WorkerRunRecord cancelRequested = current.requestCancellation(now);
        DispatchOutbox cancelOutbox = new DispatchOutbox(
                outboxId,
                tenantId,
                WorkerOutboxOperations.CANCEL,
                "WORKER_RUN",
                current.workerRunId().value(),
                current.operationId(),
                order.inputArtifactManifestRef(),
                DispatchOutboxStatus.PENDING,
                now,
                0,
                Optional.empty(),
                0,
                now,
                now);
        if (transaction.prepare(order, current, cancelRequested, cancelOutbox)) {
            return true;
        }
        return alreadyStaged(workerRuns.find(tenantId, workerRunId).orElse(null));
    }

    private boolean alreadyStaged(WorkerRunRecord run) {
        if (run == null || run.status() != WorkerRunStatus.CANCEL_REQUESTED) {
            return false;
        }
        return outboxes.findByOperation(
                        run.tenantId(), WorkerOutboxOperations.CANCEL, run.operationId())
                .filter(outbox -> exactOutbox(outbox, run))
                .isPresent();
    }

    private static boolean needsRecovery(WorkerRunRecord run) {
        return run != null
                && !run.status().isTerminal()
                && run.status() != WorkerRunStatus.CANCEL_REQUESTED
                && run.actionRunId().isPresent();
    }

    private static boolean exactCancelledOwner(
            WorkerRunRecord run, WorkOrder order, ActionRun action) {
        if (order == null || action == null
                || action.status() != ActionRunStatus.CANCELLED
                || !run.tenantId().equals(order.tenantId())
                || !run.workOrderId().equals(order.workOrderId())
                || !run.buildSessionId().equals(order.buildSessionId())
                || run.actionRunId().filter(action.runId()::equals).isEmpty()
                || !WorkerDispatchAction.ACTION_ID.equals(action.actionId())
                || !run.tenantId().value().equals(action.tenantId())
                || !run.operationId().equals(action.externalOperationId())
                || action.attemptToken() == null
                || action.attemptToken().isBlank()
                || run.attemptTokenHash()
                        .filter(WorkerDispatchOperationIds.hashAttemptToken(action.attemptToken())::equals)
                        .isEmpty()) {
            return false;
        }
        try {
            WorkerDispatchInput input = WorkerDispatchInput.from(action.input());
            return input.workOrderId().equals(run.workOrderId())
                    && input.workerRunId().equals(run.workerRunId());
        } catch (RuntimeException invalidInput) {
            return false;
        }
    }

    private static boolean exactOutbox(DispatchOutbox outbox, WorkerRunRecord run) {
        return outbox.tenantId().equals(run.tenantId())
                && WorkerOutboxOperations.CANCEL.equals(outbox.operationType())
                && "WORKER_RUN".equals(outbox.aggregateType())
                && outbox.aggregateId().equals(run.workerRunId().value())
                && outbox.operationId().equals(run.operationId())
                && outbox.outboxId().value().equals(
                        WorkerDispatchOperationIds.cancelOutboxId(run.operationId()));
    }
}
