package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.policy.DefaultPolicyGate;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import java.util.Objects;

public final class ReferenceAssemblyIntakePolicyGate implements PolicyGate {
    private final ReferenceAssemblyIntakeAdmission admission;
    private final PolicyGate baseline = new DefaultPolicyGate();
    public ReferenceAssemblyIntakePolicyGate(ReferenceAssemblyIntakeAdmission admission) { this.admission = Objects.requireNonNull(admission); }
    @Override public PolicyDecision evaluate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        try {
            if (!ReferenceAssemblyIntakeAction.definition().equals(definition)) throw new IllegalArgumentException("unknown definition");
            var base = baseline.evaluate(proposal, definition, context);
            if (!base.allowedToExecuteNow()) return base;
            admission.current(proposal, context); return PolicyDecision.allow();
        } catch (RuntimeException invalid) { return PolicyDecision.deny("Reference Assembly intake is not authorized"); }
    }
}
