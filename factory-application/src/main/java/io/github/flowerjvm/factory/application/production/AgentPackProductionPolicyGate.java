package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.policy.DefaultPolicyGate;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import java.time.Clock;
import java.util.Objects;

/** Current authority is checked before any completed-result lookup or duplicate reservation. */
public final class AgentPackProductionPolicyGate implements PolicyGate {
    private final BuildSessionRepository sessions;
    private final Clock clock;
    private final PolicyGate baseline = new DefaultPolicyGate();

    public AgentPackProductionPolicyGate(BuildSessionRepository sessions, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PolicyDecision evaluate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        if (!AgentPackProductionAction.ACTION_ID.equals(definition.actionId())) return deny();
        PolicyDecision base = baseline.evaluate(proposal, definition, context);
        if (!base.allowedToExecuteNow()) return base;
        try { AgentPackProductionAuthority.current(sessions, proposal, context, clock.instant()); return PolicyDecision.allow(); }
        catch (RuntimeException unavailable) { return deny(); }
    }

    private static PolicyDecision deny() { return PolicyDecision.deny("Agent Pack production preparation is not authorized"); }
}
