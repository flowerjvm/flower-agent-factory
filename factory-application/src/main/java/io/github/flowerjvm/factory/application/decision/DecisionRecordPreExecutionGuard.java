package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.guard.*;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import java.util.Objects;

public final class DecisionRecordPreExecutionGuard implements PreExecutionGuard {
    private final DecisionRecordAdmission admission;
    public DecisionRecordPreExecutionGuard(DecisionRecordAdmission admission) { this.admission = Objects.requireNonNull(admission); }
    @Override public PreExecutionDecision check(ActionProposal proposal, ActionDefinition definition, ExecutionContext context, PolicyDecision policy) {
        try {
            if (!DecisionRecordAction.definition().equals(definition) || !policy.allowedToExecuteNow()) throw new IllegalArgumentException();
            admission.current(proposal, context); return PreExecutionDecision.allow();
        } catch (RuntimeException stale) { return PreExecutionDecision.deny("DECISION_RECORD_STALE", "Decision authority or target changed"); }
    }
}
