package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import java.util.Objects;
import java.time.Clock;

/** Applies fake/external completion to the WorkerRun ledger before any Flower wake-up signal. */
public final class WorkerCompletionService {
    private final WorkerRunRepository workerRuns;
    private final CompletableActionRuntime actionRuntime;
    private final Clock clock;

    public WorkerCompletionService(
            WorkerRunRepository workerRuns,
            CompletableActionRuntime actionRuntime,
            Clock clock) {
        this.workerRuns = Objects.requireNonNull(workerRuns, "workerRuns");
        this.actionRuntime = Objects.requireNonNull(actionRuntime, "actionRuntime");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WorkerCompletionDisposition complete(WorkerCompletion completion) {
        return complete(completion, false);
    }

    /**
     * Completes from a callback receipt whose timestamp was assigned by the trusted ingress clock
     * and durably stored before processing. This is deliberately package-private so external
     * adapters cannot promote a provider-supplied timestamp into deadline authority.
     */
    WorkerCompletionDisposition completeFromTrustedInboxReceipt(WorkerCompletion completion) {
        return complete(completion, true);
    }

    private WorkerCompletionDisposition complete(
            WorkerCompletion completion, boolean trustedDurableReceipt) {
        Objects.requireNonNull(completion, "completion");
        WorkerRunRecord current = workerRuns
                .find(completion.tenantId(), completion.workerRunId())
                .orElseThrow(() -> new IllegalArgumentException("WORKER_CALLBACK_SCOPE_MISMATCH"));
        if (!current.operationId().equals(completion.operationId())
                || current.attemptTokenHash().isEmpty()
                || !current.attemptTokenHash().orElseThrow().equals(
                        WorkerDispatchOperationIds.hashAttemptToken(completion.attemptToken()))) {
            return WorkerCompletionDisposition.STALE_OR_CONFLICT;
        }
        if (!completion.completedAt().isBefore(current.deadlineAt())) {
            return WorkerCompletionDisposition.STALE_OR_CONFLICT;
        }
        if (current.status().isTerminal()) {
            if (sameTerminalResult(current, completion)) {
                CanonicalCompletion canonical = completeAction(
                        current, completion, trustedDurableReceipt);
                if (!canonical.submittedResultWon()
                        || !sameTerminalOutcome(current, canonical.outcome())) {
                    throw new IllegalStateException(
                            "terminal WorkerRun conflicts with the canonical ActionRun result");
                }
                return WorkerCompletionDisposition.DUPLICATE;
            }
            return WorkerCompletionDisposition.STALE_OR_CONFLICT;
        }
        if (!trustedDurableReceipt && !clock.instant().isBefore(current.deadlineAt())) {
            return WorkerCompletionDisposition.STALE_OR_CONFLICT;
        }
        // A normal callback is evidence that the publisher/adapter already accepted the
        // operation. DISPATCHING still has a pending/unknown outbox effect and must go through
        // reconciliation instead of being promoted directly to terminal.
        if (current.status() != io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus.WAITING_EXTERNAL
                && current.status()
                        != io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus.CANCEL_REQUESTED
                && current.status()
                        != io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus.RECONCILING) {
            return WorkerCompletionDisposition.STALE_OR_CONFLICT;
        }
        // Complete the Action truth first. If this call fails, the WorkerRun stays non-terminal
        // and an at-least-once callback can retry. The reverse order could let the Flow advance
        // while ActionRun/duplicate reservation/audit were still non-terminal.
        CanonicalCompletion canonical = completeAction(current, completion, trustedDurableReceipt);
        validateCanonicalTime(current, canonical.outcome(), trustedDurableReceipt);
        WorkerRunRecord next = canonical.outcome().applyTo(current);
        if (workerRuns.compareAndSet(current, next)) {
            return canonical.submittedResultWon()
                    ? WorkerCompletionDisposition.APPLIED
                    : WorkerCompletionDisposition.STALE_OR_CONFLICT;
        }
        WorkerRunRecord winner = workerRuns
                .find(completion.tenantId(), completion.workerRunId())
                .orElseThrow(() -> new IllegalStateException("WorkerRun disappeared after callback CAS"));
        for (int attempt = 0; canonical.submittedResultWon() && attempt < 8; attempt++) {
            if (!acceptsCanonicalProjection(winner.status())) {
                break;
            }
            validateCanonicalTime(winner, canonical.outcome(), trustedDurableReceipt);
            WorkerRunRecord projected = canonical.outcome().applyTo(winner);
            if (workerRuns.compareAndSet(winner, projected)) {
                return WorkerCompletionDisposition.APPLIED;
            }
            winner = workerRuns
                    .find(completion.tenantId(), completion.workerRunId())
                    .orElseThrow(() -> new IllegalStateException(
                            "WorkerRun disappeared after completion projection race"));
        }
        if (sameTerminalOutcome(winner, canonical.outcome())) {
            return canonical.submittedResultWon()
                    ? WorkerCompletionDisposition.DUPLICATE
                    : WorkerCompletionDisposition.STALE_OR_CONFLICT;
        }
        throw new IllegalStateException("ActionRun completed but WorkerRun completion CAS conflicted");
    }

    private static boolean acceptsCanonicalProjection(
            io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus status) {
        return status == io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus.WAITING_EXTERNAL
                || status
                        == io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus.CANCEL_REQUESTED
                || status == io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus.RECONCILING;
    }

    private CanonicalCompletion completeAction(
            WorkerRunRecord current,
            WorkerCompletion completion,
            boolean trustedDurableReceipt) {
        String actionRunId = current.actionRunId()
                .orElseThrow(() -> new IllegalStateException("dispatched WorkerRun has no ActionRun owner"));
        ActionExecutionResult expected = actionResult(completion);
        var submittedOutcome = WorkerCompletionActionProjection.fromActionResult(expected, current)
                .orElseThrow(() -> new IllegalStateException("Worker completion cannot be projected to ActionRun"));
        validateCanonicalTime(current, submittedOutcome, trustedDurableReceipt);
        ActionExecutionResult observed = actionRuntime.complete(
                actionRunId,
                completion.attemptToken(),
                expected);
        var outcome = WorkerCompletionActionProjection.fromActionResult(observed, current)
                .orElseThrow(() -> new IllegalStateException(
                        "ActionRun terminal result conflicts with WorkerRun completion"));
        return new CanonicalCompletion(outcome, expected.equals(observed));
    }

    private static ActionExecutionResult actionResult(WorkerCompletion completion) {
        return WorkerCompletionActionProjection.toActionResult(completion);
    }

    private static boolean sameTerminalResult(WorkerRunRecord current, WorkerCompletion completion) {
        return current.status() == completion.terminalStatus()
                && current.operationId().equals(completion.operationId())
                && current.attemptTokenHash().isPresent()
                && current.attemptTokenHash().orElseThrow().equals(
                        WorkerDispatchOperationIds.hashAttemptToken(completion.attemptToken()))
                && current.completedAt().equals(java.util.Optional.of(
                        WorkerCompletionActionProjection.canonicalTimestamp(completion.completedAt())))
                && current.resultArtifactManifestRef().equals(completion.resultArtifactManifestRef())
                && current.resultHash().equals(completion.resultHash())
                && current.code().equals(java.util.Optional.of(completion.code()))
                && current.message().equals(java.util.Optional.of(completion.message()))
                && current.retryDisposition().equals(java.util.Optional.of(completion.retryDisposition()));
    }

    private static boolean sameTerminalOutcome(
            WorkerRunRecord current,
            WorkerCompletionActionProjection.TerminalOutcome outcome) {
        return current.status() == outcome.status()
                && current.completedAt().equals(java.util.Optional.of(outcome.completedAt()))
                && current.resultArtifactManifestRef().equals(outcome.resultArtifactManifestRef())
                && current.resultHash().equals(outcome.resultHash())
                && current.code().equals(java.util.Optional.of(outcome.code()))
                && current.message().equals(java.util.Optional.of(outcome.message()))
                && current.retryDisposition().equals(java.util.Optional.of(outcome.retryDisposition()));
    }

    private static void validateCanonicalTime(
            WorkerRunRecord current,
            WorkerCompletionActionProjection.TerminalOutcome outcome,
            boolean trustedDurableReceipt) {
        boolean trustedReceiptPredatesState = trustedDurableReceipt
                && (current.status()
                                == io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus.WAITING_EXTERNAL
                        || current.status()
                                == io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus.CANCEL_REQUESTED
                        || current.status()
                                == io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus.RECONCILING)
                && current.startedAt()
                        .filter(started -> !outcome.completedAt().isBefore(started))
                        .isPresent()
                && (current.status()
                                != io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus.CANCEL_REQUESTED
                        || current.cancelRequestedAt()
                                .filter(cancelledAt -> !outcome.completedAt().isAfter(cancelledAt))
                                .isPresent());
        if (outcome.completedAt().isBefore(current.updatedAt())
                && !trustedReceiptPredatesState) {
            throw new IllegalStateException("ActionRun completion predates the WorkerRun state");
        }
        if (outcome.status() == io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus.TIMED_OUT) {
            if (outcome.completedAt().isAfter(
                    WorkerCompletionActionProjection.canonicalTimestamp(current.deadlineAt()))) {
                throw new IllegalStateException("ActionRun timeout exceeds the persisted WorkerRun deadline");
            }
        } else if (!outcome.completedAt().isBefore(current.deadlineAt())) {
            throw new IllegalStateException("ActionRun completion is outside the persisted deadline");
        }
    }

    private record CanonicalCompletion(
            WorkerCompletionActionProjection.TerminalOutcome outcome,
            boolean submittedResultWon) {}
}
