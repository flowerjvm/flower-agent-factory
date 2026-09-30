package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateVisibilityScopeResolver;
import java.util.Objects;

/** Resource/principal scope plus exact payload fingerprint closes concurrent absent-Decision cache races. */
public final class DecisionRecordVisibilityScopeResolver implements DuplicateVisibilityScopeResolver {
    private final DecisionRecordAdmission admission;
    public DecisionRecordVisibilityScopeResolver(DecisionRecordAdmission admission) { this.admission = Objects.requireNonNull(admission); }
    @Override public String resolve(ActionProposal proposal, ExecutionContext context) {
        var scope = admission.scopedIdentity(proposal, context); var input = scope.input(); var authority = scope.authority();
        return "decision-record:" + DecisionRecordAdmission.hash(authority.tenantId().value(), authority.projectId().value(),
                authority.principal(), input.decisionPointId().value(), input.subjectHash().sha256(),
                Long.toString(input.expectedDecisionPointVersion()), input.outcome().name(), input.reason().orElse(""));
    }
}
