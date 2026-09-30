package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate;
import io.github.flowerjvm.factory.application.certification.ResolvedCertifiedAgentComponent;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import java.util.Objects;

/** Pure exact-lock assembler for the concrete reference-assembly ProductLine. */
public final class ReferenceAssemblyAssembler {
    private final ReferenceAssemblyArtifactSupport artifactSupport;
    private final CertifiedAgentComponentReadGate componentReadGate;
    private final ReferenceAssemblyAdmissionPolicies admissionPolicies;

    public ReferenceAssemblyAssembler(
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            CertifiedAgentComponentReadGate componentReadGate,
            ReferenceAssemblyAdmissionPolicy admissionPolicy) {
        this(artifacts, codec, componentReadGate, ReferenceAssemblyAdmissionPolicies.of(admissionPolicy));
    }

    public ReferenceAssemblyAssembler(
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            CertifiedAgentComponentReadGate componentReadGate,
            ReferenceAssemblyAdmissionPolicies admissionPolicies) {
        this.artifactSupport = new ReferenceAssemblyArtifactSupport(artifacts, codec);
        this.componentReadGate = Objects.requireNonNull(componentReadGate, "componentReadGate");
        this.admissionPolicies = Objects.requireNonNull(admissionPolicies, "admissionPolicies");
    }

    /** Reads every input from trusted-tenant storage and stages one deterministic manifest. */
    public AssemblyResult assemble(
            TenantId trustedTenantId, CertificationArtifactLock requirementLock) {
        ComponentResolution resolution = resolveComponent(trustedTenantId, requirementLock);
        ReferenceAssemblyRequirement requirement = resolution.requirement();
        ReferenceAssemblyConsumerContract contract = resolution.consumerContract();
        ReferenceAssemblyManifest manifest = new ReferenceAssemblyManifest(
                ReferenceAssemblyManifest.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                contract.assemblyAlgorithmId(),
                requirementLock,
                requirement.consumerContract(),
                requirement.hostFixture(),
                requirement.policySnapshot(),
                requirement.component());
        CertificationArtifactLock manifestLock =
                artifactSupport.stageManifest(trustedTenantId, manifest);
        return new AssemblyResult(manifest, manifestLock);
    }

    /**
     * Resolves and revalidates the exact certified component without producing an assembly
     * artifact. The durable Flow uses this bounded operation for its explicit component-resolution
     * phase; {@link #assemble(TenantId, CertificationArtifactLock)} invokes it again so assembly
     * never relies on stale in-memory resolution state.
     */
    public ComponentResolution resolveComponent(
            TenantId trustedTenantId, CertificationArtifactLock requirementLock) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        Objects.requireNonNull(requirementLock, "requirementLock");
        ReferenceAssemblyRequirement requirement =
                artifactSupport.readRequirement(trustedTenantId, requirementLock);
        ReferenceAssemblyAdmissionPolicy admissionPolicy = admissionPolicies.find(requirement.consumerContract())
                .orElseThrow(() -> new ReferenceAssemblyException(
                        ReferenceAssemblyException.ADMISSION_POLICY_MISMATCH,
                        "Reference Assembly consumer contract has no exact trusted entry"));
        requireApprovedGraph(requirement, admissionPolicy);
        ReferenceAssemblyConsumerContract contract = artifactSupport.readConsumerContract(
                trustedTenantId, requirement.consumerContract());
        artifactSupport.readOpaque(trustedTenantId, requirement.hostFixture());
        artifactSupport.readOpaque(trustedTenantId, requirement.policySnapshot());
        ResolvedCertifiedAgentComponent component =
                resolveCertifiedComponent(trustedTenantId, requirement);
        requireCompatibility(
                trustedTenantId, requirement, contract, component, admissionPolicy);
        return new ComponentResolution(requirement, contract, component);
    }

    private ResolvedCertifiedAgentComponent resolveCertifiedComponent(
            TenantId tenantId, ReferenceAssemblyRequirement requirement) {
        try {
            return Objects.requireNonNull(
                    componentReadGate.resolve(tenantId, requirement.component()),
                    "resolved component");
        } catch (RuntimeException rejected) {
            throw new ReferenceAssemblyException(
                    ReferenceAssemblyException.COMPONENT_REJECTED,
                    "Certified Agent component failed its strict read gate",
                    rejected);
        }
    }

    static void requireCompatibility(
            TenantId tenantId,
            ReferenceAssemblyRequirement requirement,
            ReferenceAssemblyConsumerContract contract,
            ResolvedCertifiedAgentComponent resolved,
            ReferenceAssemblyAdmissionPolicy policy) {
        var component = requirement.component();
        var input = resolved.inputLock();
        var compatibility = resolved.compatibilityDescriptor();
        if (!resolved.reference().equals(component)
                || !input.tenantId().equals(tenantId)
                || !compatibility.tenantId().equals(tenantId)) {
            throw new ReferenceAssemblyException(
                    ReferenceAssemblyException.COMPONENT_MISMATCH,
                    "Resolved Agent component differs from the exact requirement reference");
        }
        if (!contract.requiredHostFixture().equals(requirement.hostFixture())
                || !contract.requiredHostFixture().equals(policy.approvedHostFixture())) {
            throw new ReferenceAssemblyException(
                    ReferenceAssemblyException.FIXTURE_MISMATCH,
                    "Consumer contract requires a different exact host fixture");
        }
        if (!contract.requiredComponentRole().equals(policy.requiredComponentRole())
                || !policy.requiredComponentRole().equals(component.componentRole())
                || !contract.requiredCertificationProfile().equals(
                        policy.requiredCertificationProfile())
                || !policy.requiredCertificationProfile().equals(component.certificationProfile())
                || !policy.requiredCertificationProfile().equals(input.certificationProfile())
                || !policy.requiredCertificationProfile().equals(
                        resolved.manifest().certificationProfile())) {
            throw new ReferenceAssemblyException(
                    ReferenceAssemblyException.COMPONENT_MISMATCH,
                    "Agent component role or certification profile is not admitted");
        }
        if (!contract.requiredAgentProductContract().equals(
                        policy.expectedAgentProductContract())
                || !policy.expectedAgentProductContract().equals(input.productContractBundle())
                || !policy.expectedAgentProductContract().equals(
                        compatibility.productContractBundle())
                || !contract.requiredApiSignatureIndex().equals(
                        policy.expectedApiSignatureIndex())
                || !policy.expectedApiSignatureIndex().equals(input.apiSignatureIndex())
                || !policy.expectedApiSignatureIndex().equals(
                        compatibility.apiSignatureIndex())) {
            throw new ReferenceAssemblyException(
                    ReferenceAssemblyException.CONTRACT_MISMATCH,
                    "Agent component product contract or API signature is not admitted");
        }
        if (!contract.requiredFlowerVersion().equals(policy.requiredFlowerVersion())
                || !policy.requiredFlowerVersion().equals(input.flowerVersion())
                || !policy.requiredFlowerVersion().equals(compatibility.flowerVersion())
                || !contract.requiredActionRuntimeVersion().equals(
                        policy.requiredActionRuntimeVersion())
                || !policy.requiredActionRuntimeVersion().equals(input.actionRuntimeVersion())
                || !policy.requiredActionRuntimeVersion().equals(
                        compatibility.actionRuntimeVersion())
                || !policy.requiredSourceLockAlgorithmId().equals(
                        input.sourceLockAlgorithmId())
                || !policy.requiredSourceLockAlgorithmId().equals(
                        compatibility.sourceLockAlgorithmId())) {
            throw new ReferenceAssemblyException(
                    ReferenceAssemblyException.RUNTIME_MISMATCH,
                    "Agent component runtime versions or source-lock algorithm are not admitted");
        }
        if (!policy.assemblyAlgorithmId().equals(contract.assemblyAlgorithmId())
                || !ReferenceAssemblyManifest.SCHEMA_VERSION.equals(policy.assemblyAlgorithmId())) {
            throw new ReferenceAssemblyException(
                    ReferenceAssemblyException.ALGORITHM_MISMATCH,
                    "Consumer contract requires a different assembly algorithm");
        }
    }

    private static void requireApprovedGraph(
            ReferenceAssemblyRequirement requirement, ReferenceAssemblyAdmissionPolicy admissionPolicy) {
        if (!requirement.consumerContract().equals(admissionPolicy.approvedConsumerContract())
                || !requirement.hostFixture().equals(admissionPolicy.approvedHostFixture())
                || !requirement.policySnapshot().equals(
                        admissionPolicy.approvedPolicySnapshot())) {
            throw new ReferenceAssemblyException(
                    ReferenceAssemblyException.ADMISSION_POLICY_MISMATCH,
                    "Reference Assembly requirement is outside the trusted admission policy");
        }
    }

    /** Exact values proven during the bounded component-resolution phase. */
    public record ComponentResolution(
            ReferenceAssemblyRequirement requirement,
            ReferenceAssemblyConsumerContract consumerContract,
            ResolvedCertifiedAgentComponent component) {
        public ComponentResolution {
            Objects.requireNonNull(requirement, "requirement");
            Objects.requireNonNull(consumerContract, "consumerContract");
            Objects.requireNonNull(component, "component");
        }
    }

    /** Canonical manifest value plus its exact content-addressed lock. */
    public record AssemblyResult(
            ReferenceAssemblyManifest manifest,
            CertificationArtifactLock manifestLock) {
        public AssemblyResult {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(manifestLock, "manifestLock");
        }
    }
}
