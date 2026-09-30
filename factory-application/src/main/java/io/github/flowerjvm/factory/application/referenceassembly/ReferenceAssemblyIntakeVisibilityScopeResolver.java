package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateVisibilityScopeResolver;
import java.util.Objects;

/** Trusted resource scope plus validated immutable payload fingerprint closes absent-row races. */
public final class ReferenceAssemblyIntakeVisibilityScopeResolver implements DuplicateVisibilityScopeResolver {
    private final ReferenceAssemblyIntakeAdmission admission;
    public ReferenceAssemblyIntakeVisibilityScopeResolver(ReferenceAssemblyIntakeAdmission admission) { this.admission = Objects.requireNonNull(admission); }
    @Override public String resolve(ActionProposal proposal, ExecutionContext context) {
        var input = admission.scopedInput(proposal, context);
        return "reference-assembly-intake:" + ReferenceAssemblyIntakeAuthority.hash(context.tenantId(), context.userId(),
                (String) context.metadata().get("resource.projectId"), (String) context.metadata().get("resource.id"),
                input.deadlineAt().toString(), input.catalogEntry().name(), input.certificationId().value(), input.sourceHash().sha256(),
                input.certificationManifest().reference().value(), input.certificationManifest().hash().sha256());
    }
}
