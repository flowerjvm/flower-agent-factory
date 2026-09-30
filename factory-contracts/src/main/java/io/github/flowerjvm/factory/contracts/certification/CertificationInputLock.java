package io.github.flowerjvm.factory.contracts.certification;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import java.util.Objects;

/** Canonical exact subject lock for one AGENT_PACK certification request. */
public record CertificationInputLock(
        String schemaVersion,
        TenantId tenantId,
        ProductLineId productLineId,
        CertifiedArtifactType artifactType,
        BuildSessionId buildSessionId,
        WorkOrderId generationWorkOrderId,
        CandidateId candidateId,
        ContentHash candidateHash,
        CertificationArtifactLock sourceManifest,
        CertificationArtifactLock dependencyLock,
        CertificationArtifactLock toolchainLock,
        CertificationArtifactLock generationInputManifest,
        CertificationArtifactLock productContractBundle,
        CertificationArtifactLock apiSignatureIndex,
        String sourceLockAlgorithmId,
        String gateProfile,
        VerificationRunId verificationRunId,
        String verificationActionRunId,
        CertificationArtifactLock verificationResultManifest,
        ContentHash verificationFixtureSetHash,
        CertificationArtifactLock policySnapshot,
        CertificationArtifactLock compatibilityDescriptor,
        String certificationProfile,
        String factoryVersion,
        String flowerVersion,
        String actionRuntimeVersion) {

    public static final String SCHEMA_VERSION = "factory.certification-input-lock.v1";

    public CertificationInputLock {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported Certification input lock schemaVersion");
        }
        Objects.requireNonNull(tenantId, "tenantId");
        productLineId = CertificationContractValues.requireAgentPackLine(productLineId);
        artifactType = CertificationContractValues.requireAgentPackType(artifactType);
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Objects.requireNonNull(generationWorkOrderId, "generationWorkOrderId");
        CertificationContractValues.requireExactIdentity(
                generationWorkOrderId.value(), "generationWorkOrderId", 128);
        Objects.requireNonNull(candidateId, "candidateId");
        CertificationContractValues.requireExactIdentity(candidateId.value(), "candidateId", 128);
        Objects.requireNonNull(candidateHash, "candidateHash");
        Objects.requireNonNull(sourceManifest, "sourceManifest");
        Objects.requireNonNull(dependencyLock, "dependencyLock");
        Objects.requireNonNull(toolchainLock, "toolchainLock");
        Objects.requireNonNull(generationInputManifest, "generationInputManifest");
        Objects.requireNonNull(productContractBundle, "productContractBundle");
        Objects.requireNonNull(apiSignatureIndex, "apiSignatureIndex");
        sourceLockAlgorithmId = CertificationContractValues.requireStableId(
                sourceLockAlgorithmId, "sourceLockAlgorithmId", 128);
        gateProfile = CertificationContractValues.requireStableId(gateProfile, "gateProfile", 128);
        Objects.requireNonNull(verificationRunId, "verificationRunId");
        CertificationContractValues.requireExactIdentity(verificationRunId.value(), "verificationRunId", 128);
        verificationActionRunId = CertificationContractValues.requireExactIdentity(
                verificationActionRunId, "verificationActionRunId", 128);
        Objects.requireNonNull(verificationResultManifest, "verificationResultManifest");
        Objects.requireNonNull(verificationFixtureSetHash, "verificationFixtureSetHash");
        Objects.requireNonNull(policySnapshot, "policySnapshot");
        Objects.requireNonNull(compatibilityDescriptor, "compatibilityDescriptor");
        certificationProfile = CertificationContractValues.requireStableId(
                certificationProfile, "certificationProfile", 128);
        factoryVersion = CertificationContractValues.requireResolvedVersion(factoryVersion, "factoryVersion");
        flowerVersion = CertificationContractValues.requireResolvedVersion(flowerVersion, "flowerVersion");
        actionRuntimeVersion = CertificationContractValues.requireResolvedVersion(
                actionRuntimeVersion, "actionRuntimeVersion");
    }

}
