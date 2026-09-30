package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionDecision;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionGuard;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import java.util.Objects;

public final class ReferenceAssemblyIntakePreExecutionGuard implements PreExecutionGuard {
    private final ReferenceAssemblyIntakeAdmission admission;
    public ReferenceAssemblyIntakePreExecutionGuard(ReferenceAssemblyIntakeAdmission admission) { this.admission = Objects.requireNonNull(admission); }
    @Override public PreExecutionDecision check(ActionProposal proposal, ActionDefinition definition, ExecutionContext context, PolicyDecision policy) {
        try {
            if (!ReferenceAssemblyIntakeAction.definition().equals(definition) || !policy.allowedToExecuteNow()) {
                throw new IllegalArgumentException("intake definition or policy is not current");
            }
            admission.current(proposal, context); return PreExecutionDecision.allow();
        } catch (RuntimeException invalid) {
            return PreExecutionDecision.deny("REFERENCE_ASSEMBLY_INTAKE_STALE", "Intake authority changed before acceptance");
        }
    }
}
