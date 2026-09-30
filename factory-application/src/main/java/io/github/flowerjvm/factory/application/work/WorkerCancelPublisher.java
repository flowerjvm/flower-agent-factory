package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchInput;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.outbox.DispatchClaimPurpose;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxRepository;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.CodingWorker;
import io.github.flowerjvm.factory.contracts.worker.WorkerAttemptToken;
import io.github.flowerjvm.factory.contracts.worker.WorkerCancelRequest;
import io.github.flowerjvm.factory.contracts.worker.WorkerLookupState;
import io.github.flowerjvm.factory.contracts.worker.WorkerOperationNotFoundException;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.factory.contracts.worker.WorkerStatusRequest;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Bounded, Action-canonical cooperative-cancel outbox publisher. */
public final class WorkerCancelPublisher {
    public static final String CANCEL_CONFIRMED = "WORKER_CANCEL_CONFIRMED";
    public static final String CANCEL_OPERATION_ABSENT = "WORKER_CANCEL_OPERATION_ABSENT";
    public static final String CANCEL_SUPERSEDED = "WORKER_CANCEL_SUPERSEDED";
    public static final String CANCEL_UNCONFIRMED = "WORKER_CANCEL_UNCONFIRMED";
    public static final String CANCEL_OWNER_INVALID = "WORKER_CANCEL_OWNER_INVALID";
    public static final String CANCEL_ACTION_ORPHANED = "WORKER_CANCEL_ACTION_ORPHANED";
    public static final String CANCEL_COMPLETION_RACE = "WORKER_CANCEL_COMPLETION_RACE";
    public static final String CANCEL_ABSENCE_UNPROVEN = "WORKER_CANCEL_ABSENCE_UNPROVEN";
    public static final String CANCEL_LATE_EFFECT_HANDOFF_STARTED =
            "WORKER_CANCEL_LATE_EFFECT_HANDOFF_STARTED";
    public static final String CANCEL_LATE_EFFECT_HANDOFF_ACKNOWLEDGED =
            "WORKER_CANCEL_LATE_EFFECT_HANDOFF_ACKNOWLEDGED";
    public static final String CANCEL_LATE_EFFECT_HANDOFF_UNCERTAIN =
            "WORKER_CANCEL_LATE_EFFECT_HANDOFF_UNCERTAIN";
    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);

    private final DispatchOutboxRepository outboxes;
    private final WorkOrderRepository workOrders;
    private final WorkerRunRepository workerRuns;
    private final RunStore runStore;
    private final CodingWorker worker;
    private final WorkerCancellationOutcomeTransaction outcomes;
    private final WorkerActionRunRecovery completionRecovery;
    private final CompletableActionRuntime actionRuntime;
    private final Clock clock;
    private final Duration lease;

    public WorkerCancelPublisher(
            DispatchOutboxRepository outboxes,
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            RunStore runStore,
            CodingWorker worker,
            WorkerCancellationOutcomeTransaction outcomes,
            CompletableActionRuntime actionRuntime,
            Clock clock) {
        this(
                outboxes, workOrders, workerRuns, runStore, worker, outcomes, actionRuntime,
                clock, DEFAULT_LEASE);
    }

    public WorkerCancelPublisher(
            DispatchOutboxRepository outboxes,
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            RunStore runStore,
            CodingWorker worker,
            WorkerCancellationOutcomeTransaction outcomes,
            CompletableActionRuntime actionRuntime,
            Clock clock,
            Duration lease) {
        this.outboxes = Objects.requireNonNull(outboxes, "outboxes");
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
        this.workerRuns = Objects.requireNonNull(workerRuns, "workerRuns");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.completionRecovery = new WorkerActionRunRecovery(workerRuns, runStore);
        this.actionRuntime = Objects.requireNonNull(actionRuntime, "actionRuntime");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lease = positive(lease);
    }

    public boolean tickOnce() {
        Instant now = clock.instant();
        String token = UUID.randomUUID().toString();
        var claimed = outboxes.claimExpiredForReconciliation(
                WorkerOutboxOperations.CANCEL, now, lease, token);
        if (claimed.isEmpty()) {
            token = UUID.randomUUID().toString();
            claimed = outboxes.claimNextForSubmission(
                    WorkerOutboxOperations.CANCEL, now, lease, token);
        }
        if (claimed.isEmpty()) {
            return false;
        }
        process(claimed.orElseThrow(), token, now);
        return true;
    }

    private void process(DispatchOutbox outbox, String claimToken, Instant now) {
        WorkerRunRecord run = findRun(outbox);
        ActionRun action = run == null ? null : run.actionRunId().flatMap(runStore::find).orElse(null);
        var order = run == null ? null : workOrders.find(outbox.tenantId(), run.workOrderId()).orElse(null);
        if (!exactOwner(outbox, run, order, action)) {
            manualReview(outbox, claimToken, run, CANCEL_OWNER_INVALID, now);
            return;
        }
        if (action.status().isTerminal() && action.status() != ActionRunStatus.CANCELLED) {
            WorkerRunRecord canonical = projectCanonicalCompletion(order, run);
            if (canonical != null
                    && canonical.status().isTerminal()
                    && completionRecovery.terminalOwnerMatches(order, canonical)) {
                outcomes.supersedeAfterTerminalCompletion(
                        canonical.tenantId(), canonical.workerRunId(), outbox.outboxId(),
                        canonical.operationId(),
                        claimToken, CANCEL_SUPERSEDED, now);
            } else {
                manualReview(outbox, claimToken, run, CANCEL_COMPLETION_RACE, now);
            }
            return;
        }
        if (action.status() != ActionRunStatus.CANCELLED) {
            // The cancel hook may have committed before the ActionRun cancellation CAS. Never call
            // the remote worker until the canonical runtime winner is visible. A fresh claim may
            // observe the short in-process race, but an expired claim proves the owner was orphaned
            // and must converge instead of cycling through reconciliation forever.
            if (outbox.claimPurpose().orElseThrow() == DispatchClaimPurpose.RECONCILE) {
                reconcileOrphanedAction(outbox, claimToken, run, order, action, now);
            }
            return;
        }
        if (outbox.claimPurpose().orElseThrow() == DispatchClaimPurpose.SUBMIT) {
            submit(outbox, claimToken, run, action, now);
        } else {
            reconcile(outbox, claimToken, run, action, now);
        }
    }

    private void reconcileOrphanedAction(
            DispatchOutbox outbox,
            String claimToken,
            WorkerRunRecord run,
            io.github.flowerjvm.factory.contracts.worker.WorkOrder order,
            ActionRun action,
            Instant now) {
        try {
            // The hook intent already exists, so another governed cancel is idempotent at the
            // domain boundary. Action Runtime 0.3.3 catches the hook's duplicate/CAS rejection
            // and still first-terminalizes WAITING_EXTERNAL as CANCELLED with a warning.
            actionRuntime.cancel(action.runId(), WorkerCancellationRequester.REASON_CODE);
        } catch (RuntimeException runtimeUnavailable) {
            // Preserve the reconciliation lease. A runtime/store outage is not authority to patch
            // ActionRun or to terminalize the other ledgers out of order.
            return;
        }
        ActionRun canonical = runStore.find(action.runId()).orElse(null);
        if (canonical == null || !exactOwner(outbox, run, order, canonical)) {
            return;
        }
        if (canonical.status() == ActionRunStatus.CANCELLED) {
            reconcile(outbox, claimToken, run, canonical, now);
        } else if (canonical.status().isTerminal()) {
            WorkerRunRecord projected = projectCanonicalCompletion(order, run);
            if (projected != null
                    && projected.status().isTerminal()
                    && completionRecovery.terminalOwnerMatches(order, projected)) {
                outcomes.supersedeAfterTerminalCompletion(
                        projected.tenantId(), projected.workerRunId(), outbox.outboxId(),
                        projected.operationId(), claimToken, CANCEL_SUPERSEDED, now);
            }
        }
        // A non-terminal result (for example RUNNING) stays claimed for a later governed retry.
    }

    private WorkerRunRecord projectCanonicalCompletion(
            io.github.flowerjvm.factory.contracts.worker.WorkOrder order,
            WorkerRunRecord run) {
        try {
            if (run.status() == WorkerRunStatus.WAITING_EXTERNAL
                    || run.status() == WorkerRunStatus.CANCEL_REQUESTED) {
                return completionRecovery.reconcileTerminalCompletion(order, run);
            }
            return run.status().isTerminal() ? run : null;
        } catch (RuntimeException malformedCanonicalResult) {
            return null;
        }
    }

    private void submit(
            DispatchOutbox outbox,
            String claimToken,
            WorkerRunRecord run,
            ActionRun action,
            Instant now) {
        try {
            var result = worker.cancel(new WorkerCancelRequest(
                    run.tenantId(), run.workOrderId(), run.workerRunId(), run.operationId(),
                    new WorkerAttemptToken(action.attemptToken()),
                    WorkerCancellationRequester.REASON_CODE));
            if (!result.workerRunId().equals(run.workerRunId())) {
                manualReview(outbox, claimToken, run, CANCEL_UNCONFIRMED, clock.instant());
            } else if (result.status() == WorkerRunStatus.CANCELLED) {
                outcomes.confirm(
                        run.tenantId(), run.workerRunId(), outbox.outboxId(), run.operationId(),
                        claimToken, stableCode(result.stableCode(), CANCEL_CONFIRMED), clock.instant());
            } else if (result.status() == WorkerRunStatus.CANCEL_REQUESTED) {
                // Cooperative workers may durably acknowledge the intent before their process has
                // stopped. Keep this submission claim intact: after lease expiry reconciliation
                // polls status and never repeats the externally effective cancel request.
                return;
            } else {
                manualReview(outbox, claimToken, run, CANCEL_UNCONFIRMED, clock.instant());
            }
        } catch (WorkerOperationNotFoundException authoritativeNoEffect) {
            confirmAbsentAfterProvenNoDispatch(
                    outbox, claimToken, run, clock.instant());
        } catch (RuntimeException uncertain) {
            // Keep DISPATCHING. Expiry permits status reconciliation, never another cancel call.
        }
    }

    private void reconcile(
            DispatchOutbox outbox,
            String claimToken,
            WorkerRunRecord run,
            ActionRun action,
            Instant now) {
        try {
            var observation = worker.status(new WorkerStatusRequest(
                    run.tenantId(), run.workOrderId(), run.workerRunId(), run.operationId(),
                    new WorkerAttemptToken(action.attemptToken())));
            if (observation.lookupState() == WorkerLookupState.NOT_FOUND) {
                confirmAbsentAfterProvenNoDispatch(outbox, claimToken, run, now);
            } else if (observation.lookupState() == WorkerLookupState.FOUND) {
                var snapshot = observation.snapshot().orElseThrow();
                if (!run.tenantId().equals(snapshot.tenantId())
                        || !run.workOrderId().equals(snapshot.workOrderId())
                        || !run.workerRunId().equals(snapshot.workerRunId())
                        || !run.operationId().equals(snapshot.operationId())) {
                    manualReview(outbox, claimToken, run, CANCEL_OWNER_INVALID, now);
                } else if (snapshot.status() == WorkerRunStatus.CANCELLED) {
                    outcomes.confirm(
                            run.tenantId(), run.workerRunId(), outbox.outboxId(), run.operationId(),
                            claimToken, CANCEL_CONFIRMED, now);
                } else if (snapshot.status().isTerminal()) {
                    manualReview(outbox, claimToken, run, CANCEL_UNCONFIRMED, now);
                } else if (outbox.lastCode().filter(CANCEL_ABSENCE_UNPROVEN::equals).isPresent()) {
                    handoffLateDiscoveredEffect(outbox, claimToken, run, action, now);
                } else if (outbox.lastCode()
                        .filter(code -> CANCEL_LATE_EFFECT_HANDOFF_STARTED.equals(code)
                                || CANCEL_LATE_EFFECT_HANDOFF_UNCERTAIN.equals(code))
                        .isPresent()) {
                    // A crash or transport ambiguity straddled the one allowed late-effect
                    // handoff. Never blindly repeat it; surface the still-running effect.
                    manualReview(outbox, claimToken, run, CANCEL_UNCONFIRMED, now);
                }
            } else if (observation.lookupState() == WorkerLookupState.UNKNOWN) {
                manualReview(outbox, claimToken, run, CANCEL_UNCONFIRMED, now);
            }
        } catch (RuntimeException unavailable) {
            // UNAVAILABLE/transport failure leaves the reconciliation lease intact for a later poll.
        }
    }

    private void confirmAbsentAfterProvenNoDispatch(
            DispatchOutbox cancelOutbox,
            String claimToken,
            WorkerRunRecord run,
            Instant observedAt) {
        run.dispatchOutboxId().ifPresent(dispatchOutboxId -> {
            boolean confirmed = outcomes.confirmAbsentAfterProvenNoDispatch(
                        run.tenantId(),
                        run.workerRunId(),
                        cancelOutbox.outboxId(),
                        dispatchOutboxId,
                        run.operationId(),
                        claimToken,
                        CANCEL_OPERATION_ABSENT,
                        observedAt);
            if (!confirmed) {
                outboxes.compareAndSet(
                        cancelOutbox,
                        cancelOutbox.recordClaimObservation(
                                claimToken, CANCEL_ABSENCE_UNPROVEN, observedAt));
            }
        });
    }

    private void handoffLateDiscoveredEffect(
            DispatchOutbox outbox,
            String claimToken,
            WorkerRunRecord run,
            ActionRun action,
            Instant now) {
        DispatchOutbox started = outbox.recordClaimObservation(
                claimToken, CANCEL_LATE_EFFECT_HANDOFF_STARTED, now);
        if (!outboxes.compareAndSet(outbox, started)) {
            return;
        }
        try {
            var result = worker.cancel(new WorkerCancelRequest(
                    run.tenantId(), run.workOrderId(), run.workerRunId(), run.operationId(),
                    new WorkerAttemptToken(action.attemptToken()),
                    WorkerCancellationRequester.REASON_CODE));
            Instant observedAt = clock.instant();
            if (!result.workerRunId().equals(run.workerRunId())) {
                manualReview(started, claimToken, run, CANCEL_UNCONFIRMED, observedAt);
            } else if (result.status() == WorkerRunStatus.CANCELLED) {
                outcomes.confirm(
                        run.tenantId(), run.workerRunId(), started.outboxId(), run.operationId(),
                        claimToken, stableCode(result.stableCode(), CANCEL_CONFIRMED), observedAt);
            } else if (result.status() == WorkerRunStatus.CANCEL_REQUESTED) {
                outboxes.compareAndSet(
                        started,
                        started.recordClaimObservation(
                                claimToken,
                                CANCEL_LATE_EFFECT_HANDOFF_ACKNOWLEDGED,
                                observedAt));
            } else {
                manualReview(started, claimToken, run, CANCEL_UNCONFIRMED, observedAt);
            }
        } catch (RuntimeException uncertain) {
            outboxes.compareAndSet(
                    started,
                    started.recordClaimObservation(
                            claimToken, CANCEL_LATE_EFFECT_HANDOFF_UNCERTAIN, clock.instant()));
        }
    }

    private void manualReview(
            DispatchOutbox outbox,
            String claimToken,
            WorkerRunRecord run,
            String code,
            Instant now) {
        if (run == null) {
            outboxes.compareAndSet(outbox, outbox.manualReview(claimToken, code, now));
            return;
        }
        outcomes.manualReview(
                run.tenantId(), run.workerRunId(), outbox.outboxId(), run.operationId(),
                claimToken, code, now);
    }

    private WorkerRunRecord findRun(DispatchOutbox outbox) {
        try {
            return workerRuns.find(outbox.tenantId(), new WorkerRunId(outbox.aggregateId())).orElse(null);
        } catch (RuntimeException invalidId) {
            return null;
        }
    }

    private static boolean exactOwner(
            DispatchOutbox outbox,
            WorkerRunRecord run,
            io.github.flowerjvm.factory.contracts.worker.WorkOrder order,
            ActionRun action) {
        if (!WorkerOutboxOperations.CANCEL.equals(outbox.operationType())
                || run == null || order == null || action == null
                || !outbox.tenantId().equals(run.tenantId())
                || !outbox.aggregateId().equals(run.workerRunId().value())
                || !outbox.operationId().equals(run.operationId())
                || !order.workOrderId().equals(run.workOrderId())
                || !order.tenantId().equals(run.tenantId())
                || run.actionRunId().filter(action.runId()::equals).isEmpty()
                || !WorkerDispatchAction.ACTION_ID.equals(action.actionId())
                || !run.tenantId().value().equals(action.tenantId())
                || action.attemptToken() == null || action.attemptToken().isBlank()
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

    private static String stableCode(String code, String fallback) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{0,127}") ? code : fallback;
    }

    private static Duration positive(Duration duration) {
        Objects.requireNonNull(duration, "lease");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return duration;
    }
}
