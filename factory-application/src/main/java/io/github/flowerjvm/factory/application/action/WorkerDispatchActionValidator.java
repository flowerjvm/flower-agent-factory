package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.validation.ActionInputValidator;
import io.github.flowerjvm.flower.action.runtime.validation.ValidationResult;

/** Strict v1 validator; unknown input fields are rejected. */
public final class WorkerDispatchActionValidator implements ActionInputValidator {
    @Override
    public ValidationResult validate(
            ActionProposal proposal,
            ActionDefinition definition,
            ExecutionContext context) {
        if (!WorkerDispatchAction.ACTION_ID.equals(definition.actionId())
                || !WorkerDispatchAction.ACTION_ID.equals(proposal.actionId())) {
            return ValidationResult.invalid("validator only accepts " + WorkerDispatchAction.ACTION_ID);
        }
        try {
            WorkerDispatchInput.from(proposal.input());
            return ValidationResult.ok();
        } catch (IllegalArgumentException exception) {
            return ValidationResult.invalid(exception.getMessage());
        }
    }
}
