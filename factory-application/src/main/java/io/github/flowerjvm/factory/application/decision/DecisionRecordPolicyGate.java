package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.policy.*;
import java.util.Objects;

public final class DecisionRecordPolicyGate implements PolicyGate {
    private final DecisionRecordAdmission admission;
    public DecisionRecordPolicyGate(DecisionRecordAdmission admission) { this.admission = Objects.requireNonNull(admission); }
    @Override public PolicyDecision evaluate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        try {
            if (!DecisionRecordAction.definition().equals(definition)) throw new IllegalArgumentException();
            // This Action records the explicitly authenticated human decision itself. Generic HIGH-write
            // approval would recursively ask for another decision; exact CLI/USER and grants are checked here.
            admission.current(proposal, context); return PolicyDecision.allow();
        } catch (RuntimeException denied) { return PolicyDecision.deny("Human decision is not authorized for the current target"); }
    }
}
