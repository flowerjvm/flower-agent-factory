package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import java.util.Map;
import java.util.Objects;

/** Action-id authorization router with a fail-closed unknown-action decision. */
public final class FactoryActionPolicyGateRouter implements PolicyGate {
    private final Map<String, PolicyGate> routes;

    public FactoryActionPolicyGateRouter(Map<String, PolicyGate> routes) {
        this.routes = Map.copyOf(Objects.requireNonNull(routes, "routes"));
    }

    @Override
    public PolicyDecision evaluate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        if (!definition.actionId().equals(proposal.actionId())) {
            return PolicyDecision.deny("proposal and definition action ids do not match");
        }
        var route = routes.get(proposal.actionId());
        return route == null
                ? PolicyDecision.deny("no policy is registered for " + proposal.actionId())
                : route.evaluate(proposal, definition, context);
    }
}
