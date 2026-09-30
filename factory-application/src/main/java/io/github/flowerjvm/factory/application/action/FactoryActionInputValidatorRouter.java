package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.validation.ActionInputValidator;
import io.github.flowerjvm.flower.action.runtime.validation.ValidationResult;
import java.util.Map;
import java.util.Objects;

/** Action-id router with no permissive fallback. */
public final class FactoryActionInputValidatorRouter implements ActionInputValidator {
    private final Map<String, ActionInputValidator> routes;

    public FactoryActionInputValidatorRouter(Map<String, ActionInputValidator> routes) {
        this.routes = Map.copyOf(Objects.requireNonNull(routes, "routes"));
    }

    @Override
    public ValidationResult validate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        if (!definition.actionId().equals(proposal.actionId())) {
            return ValidationResult.invalid("proposal and definition action ids do not match");
        }
        var route = routes.get(proposal.actionId());
        return route == null
                ? ValidationResult.invalid("no validator is registered for " + proposal.actionId())
                : route.validate(proposal, definition, context);
    }
}
