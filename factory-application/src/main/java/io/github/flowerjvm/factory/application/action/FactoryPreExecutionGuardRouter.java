package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionDecision;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionGuard;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import java.util.Map;
import java.util.Objects;

/** Action-id pre-dispatch router with no side-effecting or permissive fallback. */
public final class FactoryPreExecutionGuardRouter implements PreExecutionGuard {
    private final Map<String, PreExecutionGuard> routes;

    public FactoryPreExecutionGuardRouter(Map<String, PreExecutionGuard> routes) {
        this.routes = Map.copyOf(Objects.requireNonNull(routes, "routes"));
    }

    @Override
    public PreExecutionDecision check(
            ActionProposal proposal,
            ActionDefinition definition,
            ExecutionContext context,
            PolicyDecision policyDecision) {
        if (!definition.actionId().equals(proposal.actionId())) {
            return PreExecutionDecision.deny("ACTION_ROUTE_MISMATCH", "proposal and definition differ");
        }
        var route = routes.get(proposal.actionId());
        return route == null
                ? PreExecutionDecision.deny("ACTION_ROUTE_MISSING", "no guard is registered")
                : route.check(proposal, definition, context, policyDecision);
    }
}
