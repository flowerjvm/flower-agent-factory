package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.validation.ActionInputValidator;
import io.github.flowerjvm.flower.action.runtime.validation.ValidationResult;

/** Strict validation for the independent verification Action only. */
public final class VerificationRunActionValidator implements ActionInputValidator {
    @Override
    public ValidationResult validate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        if (!VerificationRunAction.ACTION_ID.equals(proposal.actionId())
                || !VerificationRunAction.ACTION_ID.equals(definition.actionId())) {
            return ValidationResult.invalid("validator only accepts " + VerificationRunAction.ACTION_ID);
        }
        try {
            VerificationRunInput.from(proposal.input());
            return ValidationResult.ok();
        } catch (IllegalArgumentException exception) {
            return ValidationResult.invalid(exception.getMessage());
        }
    }
}
