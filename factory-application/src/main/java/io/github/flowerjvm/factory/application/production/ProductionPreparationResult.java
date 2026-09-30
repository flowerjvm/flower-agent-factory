package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.util.Objects;
import java.util.Optional;

/** Preparation records intent or an OPEN review, never a Worker success or human approval. */
public record ProductionPreparationResult(
        String code,
        Optional<WorkOrderId> workOrderId,
        Optional<WorkerRunId> workerRunId,
        Optional<DecisionPointId> decisionPointId) {
    public ProductionPreparationResult {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("invalid production preparation code");
        }
        Objects.requireNonNull(workOrderId, "workOrderId");
        Objects.requireNonNull(workerRunId, "workerRunId");
        Objects.requireNonNull(decisionPointId, "decisionPointId");
        if (workOrderId.isPresent() != workerRunId.isPresent()
                || workOrderId.isPresent() == decisionPointId.isPresent()) {
            throw new IllegalArgumentException("one exact work attempt or review must be identified");
        }
    }
}
