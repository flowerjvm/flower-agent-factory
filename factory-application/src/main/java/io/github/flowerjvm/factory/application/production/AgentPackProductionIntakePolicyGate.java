package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.policy.DefaultPolicyGate;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import java.time.Clock;
import java.util.Objects;

/** Current creation authority is checked before completed-result visibility and reservation. */
public final class AgentPackProductionIntakePolicyGate implements PolicyGate {
    private final BuildSessionRepository sessions;
    private final Clock clock;
    private final AgentPackProductionIntakeRecipeCheck recipes;
    private final PolicyGate baseline = new DefaultPolicyGate();
    public AgentPackProductionIntakePolicyGate(BuildSessionRepository sessions, Clock clock) {
        this(sessions, clock, (tenant, input) -> { });
    }
    public AgentPackProductionIntakePolicyGate(BuildSessionRepository sessions, Clock clock,
                                             AgentPackProductionIntakeRecipeCheck recipes) {
        this.sessions = Objects.requireNonNull(sessions, "sessions"); this.clock = Objects.requireNonNull(clock, "clock");
        this.recipes = Objects.requireNonNull(recipes, "recipes");
    }
    @Override public PolicyDecision evaluate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        if (!AgentPackProductionIntakeAction.ACTION_ID.equals(definition.actionId())) return deny();
        PolicyDecision base = baseline.evaluate(proposal, definition, context);
        if (!base.allowedToExecuteNow()) return base;
        try {
            var input = AgentPackProductionIntakeAuthority.current(sessions, proposal, context, clock.instant());
            recipes.check(new TenantId(context.tenantId()), input);
            return PolicyDecision.allow();
        }
        catch (RuntimeException unavailable) { return deny(); }
    }
    private static PolicyDecision deny() { return PolicyDecision.deny("Agent Pack production intake is not authorized"); }
}
