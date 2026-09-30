package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyRepository;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateVisibilityScopeResolver;
import java.util.Objects;

/** Trusted tenant/resource scope prevents packaged results crossing Reference Assemblies. */
public final class ReferenceAssemblyReleaseVisibilityScopeResolver
        implements DuplicateVisibilityScopeResolver {
    private final ReferenceAssemblyRepository assemblies;

    public ReferenceAssemblyReleaseVisibilityScopeResolver(
            ReferenceAssemblyRepository assemblies) {
        this.assemblies = Objects.requireNonNull(assemblies, "assemblies");
    }

    @Override
    public String resolve(ActionProposal proposal, ExecutionContext context) {
        if (!ReferenceAssemblyReleaseAction.ACTION_ID.equals(proposal.actionId())) {
            throw new IllegalArgumentException(
                    "visibility resolver only accepts Reference Assembly release");
        }
        ReferenceAssemblyReleaseInput input =
                ReferenceAssemblyReleaseInput.from(proposal.input());
        if (!ReferenceAssemblyReleaseAction.RESOURCE_TYPE.equals(
                        context.metadata().get("resource.type"))
                || !input.referenceAssemblyId().value().equals(
                        context.metadata().get("resource.id"))) {
            throw new IllegalArgumentException(
                    "trusted Reference Assembly resource scope does not match the request");
        }
        TenantId tenantId = new TenantId(context.tenantId());
        var assembly = assemblies.find(tenantId, input.referenceAssemblyId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "ReferenceAssembly is not visible in tenant scope"));
        boolean exact = assembly.tenantId().equals(tenantId)
                && assembly.referenceAssemblyId().equals(input.referenceAssemblyId())
                && assembly.assemblyManifest()
                        .map(lock -> lock.hash().equals(input.assemblyManifestHash()))
                        .orElse(false)
                && assembly.inspectionReport()
                        .map(lock -> lock.hash().equals(input.inspectionReportHash()))
                        .orElse(false)
                && assembly.releaseDecisionPointId()
                        .filter(input.releaseDecisionPointId()::equals)
                        .isPresent()
                && assembly.releaseSubjectHash()
                        .filter(input.releaseSubjectHash()::equals)
                        .isPresent();
        if (!exact) {
            throw new IllegalArgumentException(
                    "ReferenceAssembly exact release locks do not match the request");
        }
        return "reference-assembly:" + assembly.referenceAssemblyId().value();
    }
}
