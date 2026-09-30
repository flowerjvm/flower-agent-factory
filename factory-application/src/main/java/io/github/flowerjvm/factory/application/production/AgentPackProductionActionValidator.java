package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.validation.ActionInputValidator;
import io.github.flowerjvm.flower.action.runtime.validation.ValidationResult;

public final class AgentPackProductionActionValidator implements ActionInputValidator {
    @Override
    public ValidationResult validate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        if (!AgentPackProductionAction.ACTION_ID.equals(proposal.actionId())
                || !AgentPackProductionAction.ACTION_ID.equals(definition.actionId())) {
            return ValidationResult.invalid("validator only accepts the registered production Action");
        }
        try { AgentPackProductionInput.from(proposal.input()); return ValidationResult.ok(); }
        catch (IllegalArgumentException invalid) { return ValidationResult.invalid(invalid.getMessage()); }
    }
}
