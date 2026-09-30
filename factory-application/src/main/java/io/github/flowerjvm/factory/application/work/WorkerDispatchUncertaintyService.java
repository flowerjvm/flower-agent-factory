package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchInput;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.time.Instant;
import java.util.Objects;

/** Action-first terminalization for an orphan/unknown external Worker effect. */
public final class WorkerDispatchUncertaintyService {
    private final WorkerRunRepository workerRuns;
    private final RunStore runStore;
    private final CompletableActionRuntime actionRuntime;

    public WorkerDispatchUncertaintyService(
            WorkerRunRepository workerRuns,
            RunStore runStore,
            CompletableActionRuntime actionRuntime) {
        this.workerRuns = Objects.requireNonNull(workerRuns, "workerRuns");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.actionRuntime = Objects.requireNonNull(actionRuntime, "actionRuntime");
    }

    public boolean manualReview(
            TenantId tenantId,
            WorkerRunId workerRunId,
            String operationId,
            String code,
            Instant observedAt) {
        WorkerRunRecord current = workerRuns.find(tenantId, workerRunId).orElse(null);
        ActionRun action = current == null
                ? null
                : current.actionRunId().flatMap(runStore::find).orElse(null);
        if (!exactOwner(current, action, tenantId, workerRunId, operationId)) {
            return false;
        }
        if (current.status().isTerminal()) {
            return current.status() == WorkerRunStatus.MANUAL_REVIEW;
        }
        var proposed = WorkerCompletionActionProjection.toManualReviewActionResult(
                current,
                stableCode(code),
                "External Coding Worker effect could not be reconciled safely",
                observedAt);
        var canonicalResult = actionRuntime.complete(action.runId(), action.attemptToken(), proposed);
        var outcome = WorkerCompletionActionProjection.fromActionResult(canonicalResult, current);
        if (outcome.isEmpty() || outcome.orElseThrow().status() != WorkerRunStatus.MANUAL_REVIEW) {
            return false;
        }
        WorkerRunRecord next = outcome.orElseThrow().applyTo(current);
        if (workerRuns.compareAndSet(current, next)) {
            return canonicalResult.equals(proposed);
        }
        WorkerRunRecord winner = workerRuns.find(tenantId, workerRunId).orElse(null);
        return winner != null
                && winner.status() == WorkerRunStatus.MANUAL_REVIEW
                && winner.code().filter(outcome.orElseThrow().code()::equals).isPresent();
    }

    private static boolean exactOwner(
            WorkerRunRecord workerRun,
            ActionRun action,
            TenantId tenantId,
            WorkerRunId workerRunId,
            String operationId) {
        if (workerRun == null || action == null
                || !workerRun.tenantId().equals(tenantId)
                || !workerRun.workerRunId().equals(workerRunId)
                || !workerRun.operationId().equals(operationId)
                || workerRun.actionRunId().filter(action.runId()::equals).isEmpty()
                || !WorkerDispatchAction.ACTION_ID.equals(action.actionId())
                || !tenantId.value().equals(action.tenantId())
                || action.attemptToken() == null
                || action.attemptToken().isBlank()
                || workerRun.attemptTokenHash()
                        .filter(WorkerDispatchOperationIds.hashAttemptToken(action.attemptToken())::equals)
                        .isEmpty()) {
            return false;
        }
        try {
            WorkerDispatchInput input = WorkerDispatchInput.from(action.input());
            return input.workOrderId().equals(workerRun.workOrderId())
                    && input.workerRunId().equals(workerRun.workerRunId());
        } catch (RuntimeException invalidInput) {
            return false;
        }
    }

    private static String stableCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            return "WORKER_DISPATCH_ORPHANED";
        }
        return code;
    }
}
