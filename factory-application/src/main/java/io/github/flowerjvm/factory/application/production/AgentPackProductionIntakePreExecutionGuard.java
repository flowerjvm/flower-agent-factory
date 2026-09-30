package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionDecision;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionGuard;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import java.time.Clock;
import java.util.Objects;

public final class AgentPackProductionIntakePreExecutionGuard implements PreExecutionGuard {
    private final BuildSessionRepository sessions;
    private final Clock clock;
    private final AgentPackProductionIntakeRecipeCheck recipes;
    public AgentPackProductionIntakePreExecutionGuard(BuildSessionRepository sessions, Clock clock) {
        this(sessions, clock, (tenant, input) -> { });
    }
    public AgentPackProductionIntakePreExecutionGuard(BuildSessionRepository sessions, Clock clock,
                                                    AgentPackProductionIntakeRecipeCheck recipes) {
        this.sessions = Objects.requireNonNull(sessions, "sessions"); this.clock = Objects.requireNonNull(clock, "clock");
        this.recipes = Objects.requireNonNull(recipes, "recipes");
    }
    @Override public PreExecutionDecision check(ActionProposal proposal, ActionDefinition definition, ExecutionContext context,
                                                 PolicyDecision policyDecision) {
        try {
            if (!AgentPackProductionIntakeAction.ACTION_ID.equals(definition.actionId()) || !policyDecision.allowedToExecuteNow()) {
                throw new IllegalArgumentException("intake policy did not authorize execution");
            }
            var input = AgentPackProductionIntakeAuthority.current(sessions, proposal, context, clock.instant());
            recipes.check(new TenantId(context.tenantId()), input);
            return PreExecutionDecision.allow();
        } catch (RuntimeException unavailable) {
            return PreExecutionDecision.deny("PRODUCTION_INTAKE_STALE", "Intake authority changed before acceptance");
        }
    }
}
