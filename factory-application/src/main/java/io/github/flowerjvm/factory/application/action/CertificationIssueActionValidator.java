package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.validation.ActionInputValidator;
import io.github.flowerjvm.flower.action.runtime.validation.ValidationResult;

/** Strict syntactic validation for Certification issuance proposals. */
public final class CertificationIssueActionValidator implements ActionInputValidator {
    @Override
    public ValidationResult validate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        if (!CertificationIssueAction.ACTION_ID.equals(proposal.actionId())
                || !CertificationIssueAction.ACTION_ID.equals(definition.actionId())) {
            return ValidationResult.invalid("validator only accepts " + CertificationIssueAction.ACTION_ID);
        }
        try {
            CertificationIssueInput.from(proposal.input());
            return ValidationResult.ok();
        } catch (IllegalArgumentException exception) {
            return ValidationResult.invalid(exception.getMessage());
        }
    }
}
