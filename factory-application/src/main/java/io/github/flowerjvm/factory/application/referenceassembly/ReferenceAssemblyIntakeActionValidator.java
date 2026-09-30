package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.validation.ActionInputValidator;
import io.github.flowerjvm.flower.action.runtime.validation.ValidationResult;

public final class ReferenceAssemblyIntakeActionValidator implements ActionInputValidator {
    @Override public ValidationResult validate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        try {
            if (!ReferenceAssemblyIntakeAction.ACTION_ID.equals(proposal.actionId()) || !ReferenceAssemblyIntakeAction.definition().equals(definition)) {
                throw new IllegalArgumentException("unknown intake definition");
            }
            ReferenceAssemblyIntakeInput.from(proposal.input()); return ValidationResult.ok();
        } catch (RuntimeException invalid) { return ValidationResult.invalid("Reference Assembly intake does not match its strict schema"); }
    }
}
