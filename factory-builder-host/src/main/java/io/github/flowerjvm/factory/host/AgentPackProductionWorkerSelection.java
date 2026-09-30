package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.production.AgentPackProductionPlan;
import io.github.flowerjvm.factory.contracts.worker.CodingWorker;
import java.util.Objects;

/** Trusted installed transport selection, never a customer-supplied capability assertion. */
public record AgentPackProductionWorkerSelection(
        String workspaceRef, String bindingId, String adapterVersion, CodingWorker worker) {
    public AgentPackProductionWorkerSelection {
        workspaceRef = text(workspaceRef);
        bindingId = text(bindingId);
        adapterVersion = text(adapterVersion);
        Objects.requireNonNull(worker, "worker");
    }

    /** May invoke a model-free process probe; call only on the intake caller, never a Flower tick. */
    public AgentPackProductionPlan.WorkerBinding snapshot() {
        return new AgentPackProductionPlan.WorkerBinding(bindingId, adapterVersion, worker.capabilities());
    }

    private static String text(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("production worker selection must be bounded non-control text");
        }
        return value;
    }
}
