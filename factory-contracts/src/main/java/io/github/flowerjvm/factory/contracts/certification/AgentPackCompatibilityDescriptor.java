package io.github.flowerjvm.factory.contracts.certification;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.util.Objects;

/** Exact, claim-free compatibility inputs that a downstream assembler can evaluate. */
public record AgentPackCompatibilityDescriptor(
        String schemaVersion,
        TenantId tenantId,
        ProductLineId productLineId,
        CertifiedArtifactType artifactType,
        CandidateId candidateId,
        ContentHash candidateHash,
        CertificationArtifactLock productContractBundle,
        CertificationArtifactLock apiSignatureIndex,
        CertificationArtifactLock dependencyLock,
        CertificationArtifactLock toolchainLock,
        String gateProfile,
        String sourceLockAlgorithmId,
        String factoryVersion,
        String flowerVersion,
        String actionRuntimeVersion) {

    public static final String SCHEMA_VERSION = "factory.agent-pack-compatibility-descriptor.v1";

    public AgentPackCompatibilityDescriptor {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported Agent Pack compatibility descriptor schemaVersion");
        }
        Objects.requireNonNull(tenantId, "tenantId");
        productLineId = CertificationContractValues.requireAgentPackLine(productLineId);
        artifactType = CertificationContractValues.requireAgentPackType(artifactType);
        Objects.requireNonNull(candidateId, "candidateId");
        CertificationContractValues.requireExactIdentity(candidateId.value(), "candidateId", 128);
        Objects.requireNonNull(candidateHash, "candidateHash");
        Objects.requireNonNull(productContractBundle, "productContractBundle");
        Objects.requireNonNull(apiSignatureIndex, "apiSignatureIndex");
        Objects.requireNonNull(dependencyLock, "dependencyLock");
        Objects.requireNonNull(toolchainLock, "toolchainLock");
        gateProfile = CertificationContractValues.requireStableId(gateProfile, "gateProfile", 128);
        sourceLockAlgorithmId = CertificationContractValues.requireStableId(
                sourceLockAlgorithmId, "sourceLockAlgorithmId", 128);
        factoryVersion = CertificationContractValues.requireResolvedVersion(factoryVersion, "factoryVersion");
        flowerVersion = CertificationContractValues.requireResolvedVersion(flowerVersion, "flowerVersion");
        actionRuntimeVersion = CertificationContractValues.requireResolvedVersion(
                actionRuntimeVersion, "actionRuntimeVersion");
    }
}
