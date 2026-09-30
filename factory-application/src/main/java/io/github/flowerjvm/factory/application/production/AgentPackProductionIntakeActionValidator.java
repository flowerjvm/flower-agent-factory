package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.validation.ActionInputValidator;
import io.github.flowerjvm.flower.action.runtime.validation.ValidationResult;

public final class AgentPackProductionIntakeActionValidator implements ActionInputValidator {
    @Override public ValidationResult validate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        if (!AgentPackProductionIntakeAction.ACTION_ID.equals(proposal.actionId())
                || !AgentPackProductionIntakeAction.ACTION_ID.equals(definition.actionId())) {
            return ValidationResult.invalid("validator only accepts the registered intake Action");
        }
        try { AgentPackProductionIntakeInput.from(proposal.input()); return ValidationResult.ok(); }
        catch (RuntimeException invalid) { return ValidationResult.invalid("intake input does not match its strict schema"); }
    }
}
