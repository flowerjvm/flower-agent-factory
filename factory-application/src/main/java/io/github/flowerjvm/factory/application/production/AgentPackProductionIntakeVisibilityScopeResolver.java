package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateVisibilityScopeResolver;
import java.util.Objects;

/** Creation-target scope, including immutable acceptance parameters to close absent-row races. */
public final class AgentPackProductionIntakeVisibilityScopeResolver implements DuplicateVisibilityScopeResolver {
    private final BuildSessionRepository sessions;
    private final String recipeId;
    public AgentPackProductionIntakeVisibilityScopeResolver(BuildSessionRepository sessions) {
        this(sessions, MaintenanceProductionRecipe.ID);
    }
    public AgentPackProductionIntakeVisibilityScopeResolver(BuildSessionRepository sessions, String recipeId) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        if (!MaintenanceRepairDemoScenario.supportedRecipe(recipeId)) throw new IllegalArgumentException("unsupported intake recipe");
        this.recipeId = recipeId;
    }
    @Override public String resolve(ActionProposal proposal, ExecutionContext context) {
        var input = AgentPackProductionIntakeAuthority.scopedInput(sessions, proposal, context);
        String original = "production-intake:" + AgentPackProductionIntakeAuthority.hash(context.tenantId(), context.userId(),
                (String) context.metadata().get("resource.projectId"), (String) context.metadata().get("resource.id"),
                input.deadlineAt().toString(), Integer.toString(input.maxRepairRounds()));
        return MaintenanceProductionRecipe.ID.equals(recipeId) ? original : original + ":" + recipeId;
    }
}
