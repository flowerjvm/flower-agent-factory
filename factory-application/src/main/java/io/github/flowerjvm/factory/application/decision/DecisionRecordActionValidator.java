package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.validation.ActionInputValidator;
import io.github.flowerjvm.flower.action.runtime.validation.ValidationResult;

public final class DecisionRecordActionValidator implements ActionInputValidator {
    @Override public ValidationResult validate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        try {
            if (!DecisionRecordAction.definition().equals(definition) || !DecisionRecordAction.ACTION_ID.equals(proposal.actionId())) throw new IllegalArgumentException();
            DecisionRecordInput.from(proposal.input()); return ValidationResult.ok();
        } catch (RuntimeException invalid) { return ValidationResult.invalid("Decision input does not match its strict schema"); }
    }
}
