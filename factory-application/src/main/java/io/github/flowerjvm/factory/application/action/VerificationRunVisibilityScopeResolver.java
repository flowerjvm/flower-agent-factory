package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateVisibilityScopeResolver;
import java.util.Objects;

/** Repository-resolved candidate scope prevents cached evidence crossing resource boundaries. */
public final class VerificationRunVisibilityScopeResolver implements DuplicateVisibilityScopeResolver {
    private final VerificationRunRepository verificationRuns;
    private final CandidateVersionRepository candidates;

    public VerificationRunVisibilityScopeResolver(
            VerificationRunRepository verificationRuns,
            CandidateVersionRepository candidates) {
        this.verificationRuns = Objects.requireNonNull(verificationRuns, "verificationRuns");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
    }

    @Override
    public String resolve(ActionProposal proposal, ExecutionContext context) {
        VerificationRunInput input = VerificationRunInput.from(proposal.input());
        if (!VerificationRunAction.RESOURCE_TYPE.equals(context.metadata().get("resource.type"))
                || !input.candidateId().value().equals(context.metadata().get("resource.id"))) {
            throw new IllegalArgumentException("trusted candidate resource scope does not match the request");
        }
        TenantId tenantId = new TenantId(context.tenantId());
        var candidate = candidates.find(tenantId, input.candidateId())
                .orElseThrow(() -> new IllegalArgumentException("candidate is not visible in tenant scope"));
        var run = verificationRuns.find(tenantId, input.verificationRunId())
                .orElseThrow(() -> new IllegalArgumentException("VerificationRun is not visible in tenant scope"));
        if (!run.candidateId().equals(candidate.candidateId())) {
            throw new IllegalArgumentException("VerificationRun does not belong to candidate");
        }
        return "candidate:" + candidate.candidateId().value();
    }
}
