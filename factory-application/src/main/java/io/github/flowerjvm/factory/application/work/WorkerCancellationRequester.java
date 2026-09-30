package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import java.util.Objects;

/** Routes trusted BuildSession cancellation through the owning governed dispatch Action. */
public final class WorkerCancellationRequester {
    public static final String REASON_CODE = "FACTORY_CANCELLATION_REQUESTED";

    private final WorkerRunRepository workerRuns;
    private final CompletableActionRuntime actionRuntime;
    private final WorkerCancellationIntentRecovery intentRecovery;

    public WorkerCancellationRequester(
            WorkerRunRepository workerRuns,
            CompletableActionRuntime actionRuntime,
            WorkerCancellationIntentRecovery intentRecovery) {
        this.workerRuns = Objects.requireNonNull(workerRuns, "workerRuns");
        this.actionRuntime = Objects.requireNonNull(actionRuntime, "actionRuntime");
        this.intentRecovery = Objects.requireNonNull(intentRecovery, "intentRecovery");
    }

    /** Compatibility constructor cannot recover a cancel-hook pre-commit failure. */
    @Deprecated(forRemoval = true)
    public WorkerCancellationRequester(
            WorkerRunRepository workerRuns, CompletableActionRuntime actionRuntime) {
        this.workerRuns = Objects.requireNonNull(workerRuns, "workerRuns");
        this.actionRuntime = Objects.requireNonNull(actionRuntime, "actionRuntime");
        this.intentRecovery = null;
    }

    public WorkerCancellationRequestDisposition request(
            TenantId tenantId, BuildSessionId buildSessionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        WorkerRunRecord run = workerRuns.findActiveByBuildSession(tenantId, buildSessionId).orElse(null);
        if (run == null || run.status().isTerminal() || run.actionRunId().isEmpty()) {
            return WorkerCancellationRequestDisposition.NO_ACTIVE_EXTERNAL_EFFECT;
        }
        try {
            actionRuntime.cancel(run.actionRunId().orElseThrow(), REASON_CODE);
        } catch (RuntimeException actionCancellationFailure) {
            if (recoverIntent(run)) {
                return WorkerCancellationRequestDisposition.CANCEL_OUTBOX_STAGED;
            }
            return WorkerCancellationRequestDisposition.CONFLICT;
        }
        WorkerRunRecord canonical = workerRuns.find(tenantId, run.workerRunId()).orElse(null);
        if (canonical == null) {
            return WorkerCancellationRequestDisposition.CONFLICT;
        }
        if (canonical.status() == WorkerRunStatus.CANCEL_REQUESTED) {
            return WorkerCancellationRequestDisposition.CANCEL_OUTBOX_STAGED;
        }
        if (canonical.status().isTerminal()) {
            return WorkerCancellationRequestDisposition.NO_ACTIVE_EXTERNAL_EFFECT;
        }
        if (recoverIntent(canonical)) {
            return WorkerCancellationRequestDisposition.CANCEL_OUTBOX_STAGED;
        }
        return WorkerCancellationRequestDisposition.CONFLICT;
    }

    private boolean recoverIntent(WorkerRunRecord run) {
        return intentRecovery != null
                && intentRecovery.ensure(run.tenantId(), run.workerRunId());
    }
}
