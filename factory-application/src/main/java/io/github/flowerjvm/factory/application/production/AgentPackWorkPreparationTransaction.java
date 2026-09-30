package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import java.util.List;
import java.util.Objects;

/**
 * Atomic durable preparation invoked only by the registered production Action executor.
 * This port neither dispatches a Worker nor creates verification, approval or certification truth.
 */
public interface AgentPackWorkPreparationTransaction {
    BuildSession accept(BuildSession pristineSession, List<Artifact> stagedArtifacts);

    PreparationOutcome prepare(
            BuildSession expectedSession,
            WorkOrder workOrder,
            WorkerRunRecord pristineRequestedRun,
            List<Artifact> stagedArtifacts);

    enum PreparationDisposition { CREATED, EXISTING_EXACT }

    record PreparationOutcome(
            PreparationDisposition disposition, WorkOrder workOrder, WorkerRunRecord workerRun) {
        public PreparationOutcome {
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(workOrder, "workOrder");
            Objects.requireNonNull(workerRun, "workerRun");
        }
    }
}
