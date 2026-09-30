package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.certification.CertificationRepository;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateVisibilityScopeResolver;
import java.util.Objects;

/** Trusted Certification resource scope prevents cached issuance results crossing resources. */
public final class CertificationIssueVisibilityScopeResolver implements DuplicateVisibilityScopeResolver {
    private final CertificationRepository certifications;

    public CertificationIssueVisibilityScopeResolver(CertificationRepository certifications) {
        this.certifications = Objects.requireNonNull(certifications, "certifications");
    }

    @Override
    public String resolve(ActionProposal proposal, ExecutionContext context) {
        if (!CertificationIssueAction.ACTION_ID.equals(proposal.actionId())) {
            throw new IllegalArgumentException("visibility resolver only accepts Certification issuance");
        }
        CertificationIssueInput input = CertificationIssueInput.from(proposal.input());
        if (!CertificationIssueAction.RESOURCE_TYPE.equals(context.metadata().get("resource.type"))
                || !input.certificationId().value().equals(context.metadata().get("resource.id"))) {
            throw new IllegalArgumentException("trusted Certification resource scope does not match the request");
        }
        TenantId tenantId = new TenantId(context.tenantId());
        var certification = certifications.find(tenantId, input.certificationId())
                .orElseThrow(() -> new IllegalArgumentException("Certification is not visible in tenant scope"));
        if (!certification.inputLock().tenantId().equals(tenantId)
                || !certification.inputLockArtifact().hash().equals(input.inputLockManifestHash())) {
            throw new IllegalArgumentException("Certification exact input lock does not match the request");
        }
        return "certification:" + certification.certificationId().value();
    }
}
