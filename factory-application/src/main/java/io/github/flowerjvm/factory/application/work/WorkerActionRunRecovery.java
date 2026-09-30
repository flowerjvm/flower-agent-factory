package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Read-only ActionRun correlation plus duplicate-safe WorkerRun completion recovery. */
public final class WorkerActionRunRecovery {
    private final WorkerRunRepository workerRuns;
    private final RunStore actionRuns;
    private final CompletableActionRuntime actionRuntime;
    private final WorkerActionDuplicateOwnerLookup duplicateOwners;

    public WorkerActionRunRecovery(WorkerRunRepository workerRuns, RunStore actionRuns) {
        this(workerRuns, actionRuns, null, WorkerActionDuplicateOwnerLookup.none());
    }

    public WorkerActionRunRecovery(
            WorkerRunRepository workerRuns,
            RunStore actionRuns,
            CompletableActionRuntime actionRuntime,
            WorkerActionDuplicateOwnerLookup duplicateOwners) {
        this.workerRuns = Objects.requireNonNull(workerRuns, "workerRuns");
        this.actionRuns = Objects.requireNonNull(actionRuns, "actionRuns");
        this.actionRuntime = actionRuntime;
        this.duplicateOwners = Objects.requireNonNull(duplicateOwners, "duplicateOwners");
    }

    /**
     * Detects a persisted Action attempt that owns the logical duplicate key but crashed before
     * the atomic WorkerRun/outbox claim. Re-dispatch is unsafe; the caller must durably stop for
     * reconciliation instead of creating another Action request.
     */
    public boolean hasUnclaimedResumableOwner(WorkOrder workOrder, WorkerRunRecord workerRun) {
        return findUnclaimedOwner(workOrder, workerRun)
                .filter(owner -> owner.disposition() == UnclaimedOwnerDisposition.RESUMABLE)
                .isPresent();
    }

    /** Finds the duplicate reservation owner even after its ActionRun became terminal. */
    public Optional<UnclaimedActionOwner> findUnclaimedOwner(
            WorkOrder workOrder,
            WorkerRunRecord workerRun) {
        requireSameScope(workOrder, workerRun);
        if (workerRun.status() != WorkerRunStatus.REQUESTED) {
            return Optional.empty();
        }
        String duplicateKey = WorkerDispatchIdempotencyKeys.derive(workOrder, workerRun);
        Optional<String> ownerRunId = duplicateOwners.findOwnerRunId(
                workerRun.tenantId().value(), WorkerDispatchAction.ACTION_ID, duplicateKey);
        Optional<ActionRun> owner = ownerRunId.flatMap(actionRuns::find);
        if (ownerRunId.isPresent() && owner.isEmpty()) {
            return Optional.of(new UnclaimedActionOwner(
                    ownerRunId.orElseThrow(),
                    UnclaimedOwnerDisposition.TERMINAL_EFFECT_UNCERTAIN,
                    "WORKER_ACTION_ORPHAN_EFFECT_UNCERTAIN",
                    "Duplicate owner ActionRun is missing; dispatch effect requires manual reconciliation"));
        }
        if (owner.isEmpty() && actionRuns.supportsResumableRuns()) {
            owner = actionRuns.findResumable(workerRun.tenantId().value()).stream()
                    .filter(run -> duplicateKey.equals(run.duplicateKey()))
                    .filter(run -> baseOwnerMatches(run, workOrder, workerRun))
                    .findFirst();
        }
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        ActionRun canonical = owner.orElseThrow();
        if (!duplicateKey.equals(canonical.duplicateKey())
                || !baseOwnerMatches(canonical, workOrder, workerRun)) {
            throw new IllegalStateException("WORKER_ACTION_DUPLICATE_OWNER_MISMATCH");
        }
        if (!canonical.status().isTerminal()) {
            return Optional.of(new UnclaimedActionOwner(
                    canonical.runId(),
                    UnclaimedOwnerDisposition.RESUMABLE,
                    "WORKER_ACTION_ORPHAN_RESUMABLE",
                    "ActionRun reserved the dispatch key before the WorkerRun/outbox claim"));
        }
        boolean knownNoEffect = canonical.externalOperationId().isBlank()
                && canonical.result() != null
                && canonical.result().terminal()
                && !canonical.result().terminalSuccess();
        return Optional.of(new UnclaimedActionOwner(
                canonical.runId(),
                knownNoEffect
                        ? UnclaimedOwnerDisposition.TERMINAL_NO_EFFECT
                        : UnclaimedOwnerDisposition.TERMINAL_EFFECT_UNCERTAIN,
                knownNoEffect
                        ? "WORKER_ACTION_ORPHAN_NO_EFFECT"
                        : "WORKER_ACTION_ORPHAN_EFFECT_UNCERTAIN",
                knownNoEffect
                        ? "Terminal ActionRun failed before the durable WorkerRun/outbox claim"
                        : "Terminal ActionRun is not backed by a durable WorkerRun/outbox claim"));
    }

    /**
     * Repairs the Action-terminal/WorkerRun-nonterminal crash window from canonical Action output.
     * Returns the current snapshot when the Action is not terminal yet.
     */
    public WorkerRunRecord reconcileTerminalCompletion(
            WorkOrder workOrder,
            WorkerRunRecord workerRun) {
        requireSameScope(workOrder, workerRun);
        if (!actionRuns.supportsResumableRuns()
                || (workerRun.status() != WorkerRunStatus.WAITING_EXTERNAL
                        && workerRun.status() != WorkerRunStatus.CANCEL_REQUESTED)) {
            return workerRun;
        }
        ActionRun owner = workerRun.actionRunId()
                .flatMap(actionRuns::find)
                .orElseThrow(() -> new IllegalStateException("WORKER_ACTION_OWNER_NOT_FOUND"));
        if (!baseOwnerMatches(owner, workOrder, workerRun)
                || !workerRun.operationId().equals(owner.externalOperationId())
                || workerRun.attemptTokenHash()
                        .filter(WorkerDispatchOperationIds.hashAttemptToken(owner.attemptToken())::equals)
                        .isEmpty()) {
            throw new IllegalStateException("WORKER_ACTION_OWNER_MISMATCH");
        }
        if (!owner.status().isTerminal()) {
            return workerRun;
        }
        var completion = WorkerCompletionActionProjection
                .fromTerminalActionOutcome(owner, workerRun)
                .orElseThrow(() -> new IllegalStateException("WORKER_ACTION_RESULT_MISMATCH"));
        validateCanonicalTime(workOrder, workerRun, completion);
        return persistCanonical(workerRun, completion);
    }

    /**
     * At the exact persisted deadline, proposes TIMED_OUT to the same ActionRun terminal CAS as a
     * callback and projects whichever Action result won back to the WorkerRun ledger.
     */
    public WorkerRunRecord reconcileDeadline(
            WorkOrder workOrder,
            WorkerRunRecord workerRun,
            Instant observedAt) {
        return reconcileDeadline(workOrder, workerRun, workerRun.deadlineAt(), observedAt);
    }

    /**
     * Proposes the earliest enclosing deadline to the ActionRun first-terminal CAS.
     *
     * <p>{@code effectiveDeadline} is normally
     * {@code min(BuildSession.deadlineAt, WorkOrder.deadlineAt, WorkerRun.deadlineAt)}. Persisting
     * that cutoff in the Action result lets a callback and every enclosing timeout converge on one
     * canonical WorkerRun outcome, including after a crash between the two ledger CAS operations.
     */
    public WorkerRunRecord reconcileDeadline(
            WorkOrder workOrder,
            WorkerRunRecord workerRun,
            Instant effectiveDeadline,
            Instant observedAt) {
        requireSameScope(workOrder, workerRun);
        Objects.requireNonNull(effectiveDeadline, "effectiveDeadline");
        Objects.requireNonNull(observedAt, "observedAt");
        if (workerRun.status().isTerminal() || workerRun.status() != WorkerRunStatus.WAITING_EXTERNAL) {
            return workerRun;
        }
        Instant canonicalDeadline = WorkerCompletionActionProjection.canonicalTimestamp(effectiveDeadline);
        if (canonicalDeadline.isAfter(
                        WorkerCompletionActionProjection.canonicalTimestamp(workerRun.deadlineAt()))
                || canonicalDeadline.isAfter(
                        WorkerCompletionActionProjection.canonicalTimestamp(workOrder.deadlineAt()))) {
            throw new IllegalArgumentException(
                    "effectiveDeadline must not exceed the WorkOrder or WorkerRun deadline");
        }
        if (canonicalDeadline.isBefore(
                WorkerCompletionActionProjection.canonicalTimestamp(workerRun.updatedAt()))) {
            throw new IllegalArgumentException("effectiveDeadline must not predate the WorkerRun state");
        }
        if (WorkerCompletionActionProjection.canonicalTimestamp(observedAt).isBefore(canonicalDeadline)) {
            throw new IllegalArgumentException("Effective worker execution deadline has not elapsed");
        }
        if (actionRuntime == null) {
            throw new IllegalStateException("Completable Action Runtime is required for deadline reconciliation");
        }
        ActionRun owner = requireBoundOwner(workOrder, workerRun);
        ActionExecutionResult observed = actionRuntime.complete(
                owner.runId(),
                owner.attemptToken(),
                WorkerCompletionActionProjection.toTimeoutActionResult(workerRun, canonicalDeadline));
        var canonical = WorkerCompletionActionProjection.fromActionResult(observed, workerRun)
                .orElseThrow(() -> new IllegalStateException("WORKER_ACTION_RESULT_MISMATCH"));
        validateCanonicalTime(workOrder, workerRun, canonical);
        return persistCanonical(workerRun, canonical);
    }

    /** Fail-closed proof used before a terminal WorkerRun is consumed by the Flow. */
    public boolean terminalOwnerMatches(WorkOrder workOrder, WorkerRunRecord workerRun) {
        requireSameScope(workOrder, workerRun);
        if (!actionRuns.supportsResumableRuns()) {
            return true;
        }
        if (!workerRun.status().isTerminal() || workerRun.actionRunId().isEmpty()) {
            return false;
        }
        return workerRun.actionRunId()
                .flatMap(actionRuns::find)
                .filter(owner -> baseOwnerMatches(owner, workOrder, workerRun))
                .filter(owner -> workerRun.operationId().equals(owner.externalOperationId()))
                .filter(owner -> WorkerCompletionActionProjection.fromTerminalActionOutcome(owner, workerRun)
                        .filter(completion -> sameCompletion(workerRun, completion))
                        .isPresent())
                .isPresent();
    }

    private static boolean baseOwnerMatches(
            ActionRun actionRun,
            WorkOrder workOrder,
            WorkerRunRecord workerRun) {
        Map<String, Object> input = actionRun.input();
        long claimedVersion = longValue(input.get(WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION));
        boolean versionMatches = workerRun.status() == WorkerRunStatus.REQUESTED
                ? claimedVersion == workerRun.version()
                : claimedVersion >= 0 && claimedVersion < workerRun.version();
        return workerRun.tenantId().value().equals(actionRun.tenantId())
                && WorkerDispatchAction.ACTION_ID.equals(actionRun.actionId())
                && workOrder.workOrderId().value().equals(stringValue(input.get(WorkerDispatchAction.WORK_ORDER_ID)))
                && workerRun.workerRunId().value().equals(stringValue(input.get(WorkerDispatchAction.WORKER_RUN_ID)))
                && versionMatches;
    }

    private ActionRun requireBoundOwner(WorkOrder workOrder, WorkerRunRecord workerRun) {
        ActionRun owner = workerRun.actionRunId()
                .flatMap(actionRuns::find)
                .orElseThrow(() -> new IllegalStateException("WORKER_ACTION_OWNER_NOT_FOUND"));
        if (!baseOwnerMatches(owner, workOrder, workerRun)
                || !workerRun.operationId().equals(owner.externalOperationId())
                || workerRun.attemptTokenHash()
                        .filter(WorkerDispatchOperationIds.hashAttemptToken(owner.attemptToken())::equals)
                        .isEmpty()) {
            throw new IllegalStateException("WORKER_ACTION_OWNER_MISMATCH");
        }
        return owner;
    }

    private WorkerRunRecord persistCanonical(
            WorkerRunRecord workerRun,
            WorkerCompletionActionProjection.TerminalOutcome completion) {
        WorkerRunRecord next = completion.applyTo(workerRun);
        if (workerRuns.compareAndSet(workerRun, next)) {
            return next;
        }
        WorkerRunRecord winner = workerRuns
                .find(workerRun.tenantId(), workerRun.workerRunId())
                .orElseThrow(() -> new IllegalStateException("WorkerRun disappeared during Action reconciliation"));
        if (sameCompletion(winner, completion)) {
            return winner;
        }
        throw new IllegalStateException("WORKER_ACTION_RECONCILIATION_CONFLICT");
    }

    private static void validateCanonicalTime(
            WorkOrder workOrder,
            WorkerRunRecord workerRun,
            WorkerCompletionActionProjection.TerminalOutcome completion) {
        boolean receiptPredatesState = (workerRun.status() == WorkerRunStatus.WAITING_EXTERNAL
                        || workerRun.status() == WorkerRunStatus.CANCEL_REQUESTED)
                && workerRun.startedAt()
                        .filter(started -> !completion.completedAt().isBefore(started))
                        .isPresent()
                && (workerRun.status() != WorkerRunStatus.CANCEL_REQUESTED
                        || workerRun.cancelRequestedAt()
                                .filter(cancelledAt -> !completion.completedAt().isAfter(cancelledAt))
                                .isPresent());
        if (completion.completedAt().isBefore(workerRun.updatedAt())
                && !receiptPredatesState) {
            throw new IllegalStateException("WORKER_ACTION_COMPLETION_OUTSIDE_DEADLINE");
        }
        if (completion.status() == WorkerRunStatus.TIMED_OUT) {
            if (completion.completedAt().isAfter(
                            WorkerCompletionActionProjection.canonicalTimestamp(workerRun.deadlineAt()))
                    || completion.completedAt().isAfter(
                            WorkerCompletionActionProjection.canonicalTimestamp(workOrder.deadlineAt()))) {
                throw new IllegalStateException("WORKER_ACTION_COMPLETION_OUTSIDE_DEADLINE");
            }
        } else if (!completion.completedAt().isBefore(workerRun.deadlineAt())) {
            throw new IllegalStateException("WORKER_ACTION_COMPLETION_OUTSIDE_DEADLINE");
        }
    }

    private static boolean sameCompletion(
            WorkerRunRecord workerRun,
            WorkerCompletionActionProjection.TerminalOutcome completion) {
        return workerRun.status() == completion.status()
                && workerRun.completedAt().equals(java.util.Optional.of(completion.completedAt()))
                && workerRun.resultArtifactManifestRef().equals(completion.resultArtifactManifestRef())
                && workerRun.resultHash().equals(completion.resultHash())
                && workerRun.code().equals(java.util.Optional.of(completion.code()))
                && workerRun.message().equals(java.util.Optional.of(completion.message()))
                && workerRun.retryDisposition().equals(java.util.Optional.of(completion.retryDisposition()));
    }

    public enum UnclaimedOwnerDisposition {
        RESUMABLE,
        TERMINAL_NO_EFFECT,
        TERMINAL_EFFECT_UNCERTAIN
    }

    public record UnclaimedActionOwner(
            String actionRunId,
            UnclaimedOwnerDisposition disposition,
            String code,
            String message) {

        public UnclaimedActionOwner {
            actionRunId = requireText(actionRunId, "actionRunId");
            Objects.requireNonNull(disposition, "disposition");
            code = requireText(code, "code");
            message = requireText(message, "message");
        }
    }

    private static void requireSameScope(WorkOrder workOrder, WorkerRunRecord workerRun) {
        Objects.requireNonNull(workOrder, "workOrder");
        Objects.requireNonNull(workerRun, "workerRun");
        if (!workOrder.tenantId().equals(workerRun.tenantId())
                || !workOrder.buildSessionId().equals(workerRun.buildSessionId())
                || !workOrder.workOrderId().equals(workerRun.workOrderId())) {
            throw new IllegalArgumentException("WorkerRun is outside the WorkOrder scope");
        }
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return Long.MIN_VALUE;
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
