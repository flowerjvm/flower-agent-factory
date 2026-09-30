package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionDecision;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionGuard;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import java.time.Clock;
import java.util.Objects;

public final class AgentPackProductionPreExecutionGuard implements PreExecutionGuard {
    private final BuildSessionRepository sessions;
    private final Clock clock;
    public AgentPackProductionPreExecutionGuard(BuildSessionRepository sessions, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions"); this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PreExecutionDecision check(ActionProposal proposal, ActionDefinition definition, ExecutionContext context,
                                       PolicyDecision policyDecision) {
        try {
            if (!AgentPackProductionAction.ACTION_ID.equals(definition.actionId()) || !policyDecision.allowedToExecuteNow()) {
                throw new IllegalArgumentException("production policy did not authorize execution");
            }
            AgentPackProductionAuthority.current(sessions, proposal, context, clock.instant());
            return PreExecutionDecision.allow();
        } catch (RuntimeException unavailable) {
            return PreExecutionDecision.deny("PRODUCTION_PREPARATION_STALE", "Production authority changed before preparation");
        }
    }
}
