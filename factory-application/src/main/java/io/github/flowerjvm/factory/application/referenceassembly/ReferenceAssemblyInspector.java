package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate;
import io.github.flowerjvm.factory.application.certification.ResolvedCertifiedAgentComponent;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyInspectionCheck;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyInspectionReport;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Independent stored-graph inspector for a staged Reference Assembly manifest. */
public final class ReferenceAssemblyInspector {
    private final ReferenceAssemblyArtifactSupport artifactSupport;
    private final CertifiedAgentComponentReadGate componentReadGate;
    private final ReferenceAssemblyAdmissionPolicies admissionPolicies;

    public ReferenceAssemblyInspector(
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            CertifiedAgentComponentReadGate componentReadGate,
            ReferenceAssemblyAdmissionPolicy admissionPolicy) {
        this(artifacts, codec, componentReadGate, ReferenceAssemblyAdmissionPolicies.of(admissionPolicy));
    }

    public ReferenceAssemblyInspector(
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            CertifiedAgentComponentReadGate componentReadGate,
            ReferenceAssemblyAdmissionPolicies admissionPolicies) {
        this.artifactSupport = new ReferenceAssemblyArtifactSupport(artifacts, codec);
        this.componentReadGate = Objects.requireNonNull(componentReadGate, "componentReadGate");
        this.admissionPolicies = Objects.requireNonNull(admissionPolicies, "admissionPolicies");
    }

    /** Re-reads the manifest and complete graph; no assembler result object is accepted. */
    public InspectionResult inspect(
            TenantId trustedTenantId, CertificationArtifactLock assemblyManifestLock) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        Objects.requireNonNull(assemblyManifestLock, "assemblyManifestLock");
        ReferenceAssemblyManifest manifest =
                artifactSupport.readManifest(trustedTenantId, assemblyManifestLock);
        ReferenceAssemblyRequirement requirement =
                artifactSupport.readRequirement(trustedTenantId, manifest.requirement());
        ReferenceAssemblyConsumerContract contract = artifactSupport.readConsumerContract(
                trustedTenantId, manifest.consumerContract());
        artifactSupport.readOpaque(trustedTenantId, manifest.hostFixture());
        artifactSupport.readOpaque(trustedTenantId, manifest.policySnapshot());
        rereadDifferentRequirementEdges(trustedTenantId, manifest, requirement);

        ResolvedCertifiedAgentComponent resolved = resolveForInspection(
                trustedTenantId, manifest);
        List<ReferenceAssemblyInspectionCheck> checks = checks(
                trustedTenantId,
                manifest,
                requirement,
                contract,
                resolved,
                admissionPolicies.find(requirement.consumerContract()).orElse(null));
        boolean passed = checks.stream().allMatch(ReferenceAssemblyInspectionCheck::passed);
        ReferenceAssemblyInspectionReport report = new ReferenceAssemblyInspectionReport(
                ReferenceAssemblyInspectionReport.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                assemblyManifestLock,
                manifest.consumerContract(),
                manifest.hostFixture(),
                manifest.policySnapshot(),
                manifest.component(),
                checks,
                passed
                        ? ReferenceAssemblyInspectionReport.PASSED
                        : ReferenceAssemblyInspectionReport.REJECTED);
        CertificationArtifactLock reportLock =
                artifactSupport.stageInspection(trustedTenantId, report);
        return new InspectionResult(report, reportLock);
    }

    private ResolvedCertifiedAgentComponent resolveForInspection(
            TenantId tenantId, ReferenceAssemblyManifest manifest) {
        try {
            return componentReadGate.resolve(tenantId, manifest.component());
        } catch (RuntimeException rejected) {
            return null;
        }
    }

    private static List<ReferenceAssemblyInspectionCheck> checks(
            TenantId tenantId,
            ReferenceAssemblyManifest manifest,
            ReferenceAssemblyRequirement requirement,
            ReferenceAssemblyConsumerContract contract,
            ResolvedCertifiedAgentComponent resolved,
            ReferenceAssemblyAdmissionPolicy policy) {
        if (policy == null) {
            return ReferenceAssemblyInspectionReport.REQUIRED_CHECK_IDS.stream()
                    .map(id -> check(id, false)).toList();
        }
        boolean componentResolved = resolved != null;
        boolean exactResolvedComponent = componentResolved
                && resolved.reference().equals(manifest.component())
                && resolved.inputLock().tenantId().equals(tenantId)
                && resolved.compatibilityDescriptor().tenantId().equals(tenantId);
        boolean apiSignature = componentResolved
                && contract.requiredApiSignatureIndex().equals(policy.expectedApiSignatureIndex())
                && policy.expectedApiSignatureIndex().equals(
                        resolved.inputLock().apiSignatureIndex())
                && policy.expectedApiSignatureIndex().equals(
                        resolved.compatibilityDescriptor().apiSignatureIndex());
        boolean componentRole = componentResolved
                && contract.requiredComponentRole().equals(policy.requiredComponentRole())
                && policy.requiredComponentRole().equals(manifest.component().componentRole())
                && contract.requiredCertificationProfile().equals(
                        policy.requiredCertificationProfile())
                && policy.requiredCertificationProfile().equals(
                        manifest.component().certificationProfile())
                && policy.requiredCertificationProfile().equals(
                        resolved.inputLock().certificationProfile())
                && policy.requiredCertificationProfile().equals(
                        resolved.manifest().certificationProfile());
        boolean consumerContract = componentResolved
                && manifest.consumerContract().equals(requirement.consumerContract())
                && manifest.consumerContract().equals(policy.approvedConsumerContract())
                && contract.requiredAgentProductContract().equals(
                        policy.expectedAgentProductContract())
                && policy.expectedAgentProductContract().equals(
                        resolved.inputLock().productContractBundle())
                && policy.expectedAgentProductContract().equals(
                        resolved.compatibilityDescriptor().productContractBundle())
                && contract.assemblyAlgorithmId().equals(policy.assemblyAlgorithmId())
                && policy.assemblyAlgorithmId().equals(manifest.assemblyAlgorithmId());
        boolean hostFixture = manifest.hostFixture().equals(requirement.hostFixture())
                && manifest.hostFixture().equals(policy.approvedHostFixture())
                && contract.requiredHostFixture().equals(policy.approvedHostFixture());
        boolean manifestLocks = manifest.consumerContract().equals(requirement.consumerContract())
                && manifest.hostFixture().equals(requirement.hostFixture())
                && manifest.policySnapshot().equals(requirement.policySnapshot())
                && manifest.component().equals(requirement.component())
                && manifest.policySnapshot().equals(policy.approvedPolicySnapshot());
        boolean runtimeVersion = componentResolved
                && contract.requiredFlowerVersion().equals(policy.requiredFlowerVersion())
                && policy.requiredFlowerVersion().equals(
                        resolved.inputLock().flowerVersion())
                && policy.requiredFlowerVersion().equals(
                        resolved.compatibilityDescriptor().flowerVersion())
                && contract.requiredActionRuntimeVersion().equals(
                        policy.requiredActionRuntimeVersion())
                && policy.requiredActionRuntimeVersion().equals(
                        resolved.inputLock().actionRuntimeVersion())
                && policy.requiredActionRuntimeVersion().equals(
                        resolved.compatibilityDescriptor().actionRuntimeVersion())
                && policy.requiredSourceLockAlgorithmId().equals(
                        resolved.inputLock().sourceLockAlgorithmId())
                && policy.requiredSourceLockAlgorithmId().equals(
                        resolved.compatibilityDescriptor().sourceLockAlgorithmId());
        return List.of(
                check("api-signature", apiSignature),
                check("component-certification", exactResolvedComponent),
                check("component-role", componentRole),
                check("consumer-contract", consumerContract),
                check("host-fixture", hostFixture),
                check("manifest-locks", manifestLocks),
                check("runtime-version", runtimeVersion));
    }

    private void rereadDifferentRequirementEdges(
            TenantId tenantId,
            ReferenceAssemblyManifest manifest,
            ReferenceAssemblyRequirement requirement) {
        if (!requirement.consumerContract().equals(manifest.consumerContract())) {
            artifactSupport.readConsumerContract(tenantId, requirement.consumerContract());
        }
        if (!requirement.hostFixture().equals(manifest.hostFixture())) {
            artifactSupport.readOpaque(tenantId, requirement.hostFixture());
        }
        if (!requirement.policySnapshot().equals(manifest.policySnapshot())) {
            artifactSupport.readOpaque(tenantId, requirement.policySnapshot());
        }
    }

    private static ReferenceAssemblyInspectionCheck check(String checkId, boolean passed) {
        return new ReferenceAssemblyInspectionCheck(
                checkId,
                passed,
                "REFERENCE_ASSEMBLY_"
                        + checkId.replace('-', '_').toUpperCase(Locale.ROOT)
                        + (passed ? "_PASSED" : "_FAILED"));
    }

    /** Canonical independent inspection value plus its exact content-addressed lock. */
    public record InspectionResult(
            ReferenceAssemblyInspectionReport report,
            CertificationArtifactLock reportLock) {
        public InspectionResult {
            Objects.requireNonNull(report, "report");
            Objects.requireNonNull(reportLock, "reportLock");
        }

        public boolean passed() {
            return ReferenceAssemblyInspectionReport.PASSED.equals(report.status());
        }
    }
}
