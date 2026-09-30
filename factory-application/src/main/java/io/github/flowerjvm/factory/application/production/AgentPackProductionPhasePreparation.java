package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import java.util.Objects;
import java.util.Optional;

/** Only opted-in, immutable-plan orders acquire the new registered production preparation path. */
public final class AgentPackProductionPhasePreparation {
    private final ArtifactStore artifacts;
    private final ActionBackedAgentPackProductionLauncher launcher;

    public AgentPackProductionPhasePreparation(ArtifactStore artifacts, ActionBackedAgentPackProductionLauncher launcher) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    public Optional<ActionExecutionResult> prepare(BuildSession session) {
        // Historical PR3/PR4 sessions without this intake contract retain their original behavior.
        if (!manages(session)) return Optional.empty();
        return Optional.of(launcher.prepare(session));
    }

    public boolean requiresCurrentRevision(BuildSession session, WorkOrder order) {
        return manages(session) && order.revision() != session.repairRound() + 1;
    }

    private boolean manages(BuildSession session) {
        return artifacts.find(session.tenantId(), AgentPackProductionService.planReference(
                session.tenantId(), session.buildSessionId())).isPresent();
    }
}
