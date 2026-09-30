package io.github.flowerjvm.factory.contracts.certification;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import java.util.Objects;

/** Minimal canonical evidence index proving what exact locks an AGENT_PACK certification used. */
public record CertificationEvidenceManifest(
        String schemaVersion,
        TenantId tenantId,
        ProductLineId productLineId,
        CertifiedArtifactType artifactType,
        CertificationId certificationId,
        CandidateId candidateId,
        ContentHash candidateHash,
        ContentHash inputLockManifestHash,
        VerificationRunId verificationRunId,
        String verificationActionRunId,
        ContentHash verificationResultManifestHash,
        ContentHash compatibilityDescriptorHash,
        String certificationProfile,
        String factoryVersion,
        String flowerVersion,
        String actionRuntimeVersion) {

    public static final String SCHEMA_VERSION = "factory.certification-evidence-manifest.v1";

    public CertificationEvidenceManifest {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported Certification evidence schemaVersion");
        }
        Objects.requireNonNull(tenantId, "tenantId");
        productLineId = CertificationContractValues.requireAgentPackLine(productLineId);
        artifactType = CertificationContractValues.requireAgentPackType(artifactType);
        Objects.requireNonNull(certificationId, "certificationId");
        CertificationContractValues.requireExactIdentity(certificationId.value(), "certificationId", 128);
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(candidateHash, "candidateHash");
        Objects.requireNonNull(inputLockManifestHash, "inputLockManifestHash");
        Objects.requireNonNull(verificationRunId, "verificationRunId");
        verificationActionRunId = CertificationContractValues.requireExactIdentity(
                verificationActionRunId, "verificationActionRunId", 128);
        Objects.requireNonNull(verificationResultManifestHash, "verificationResultManifestHash");
        Objects.requireNonNull(compatibilityDescriptorHash, "compatibilityDescriptorHash");
        certificationProfile = CertificationContractValues.requireStableId(
                certificationProfile, "certificationProfile", 128);
        factoryVersion = CertificationContractValues.requireResolvedVersion(factoryVersion, "factoryVersion");
        flowerVersion = CertificationContractValues.requireResolvedVersion(flowerVersion, "flowerVersion");
        actionRuntimeVersion = CertificationContractValues.requireResolvedVersion(
                actionRuntimeVersion, "actionRuntimeVersion");
    }

}
