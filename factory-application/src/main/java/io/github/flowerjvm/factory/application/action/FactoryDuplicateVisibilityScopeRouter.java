package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateVisibilityScopeResolver;
import java.util.Map;
import java.util.Objects;

/** Action-id visibility router; unknown actions cannot fall back to tenant-wide cached results. */
public final class FactoryDuplicateVisibilityScopeRouter implements DuplicateVisibilityScopeResolver {
    private final Map<String, DuplicateVisibilityScopeResolver> routes;

    public FactoryDuplicateVisibilityScopeRouter(Map<String, DuplicateVisibilityScopeResolver> routes) {
        this.routes = Map.copyOf(Objects.requireNonNull(routes, "routes"));
    }

    @Override
    public String resolve(ActionProposal proposal, ExecutionContext context) {
        var route = routes.get(proposal.actionId());
        if (route == null) {
            throw new IllegalArgumentException("no duplicate visibility route for " + proposal.actionId());
        }
        return route.resolve(proposal, context);
    }
}
