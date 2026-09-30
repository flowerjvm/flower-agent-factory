package io.github.flowerjvm.factory.contracts.certification;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import java.util.Objects;

/** Exact downstream input lock for one certified AGENT_PACK component. */
public record CertifiedAgentComponentRef(
        String schemaVersion,
        String componentRole,
        ProductLineId productLineId,
        CertifiedArtifactType artifactType,
        CertificationId certificationId,
        CertificationArtifactLock certificationManifest,
        CandidateId candidateId,
        ContentHash candidateHash,
        CertificationArtifactLock sourceManifest,
        CertificationArtifactLock inputLockManifest,
        VerificationRunId verificationRunId,
        CertificationArtifactLock verificationResultManifest,
        CertificationArtifactLock compatibilityDescriptor,
        CertificationArtifactLock certificationEvidence,
        String certificationProfile) {

    public static final String SCHEMA_VERSION = "factory.certified-agent-component-ref.v1";

    public CertifiedAgentComponentRef {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported Certified Agent Component ref schemaVersion");
        }
        componentRole = CertificationContractValues.requireStableId(componentRole, "componentRole", 128);
        productLineId = CertificationContractValues.requireAgentPackLine(productLineId);
        artifactType = CertificationContractValues.requireAgentPackType(artifactType);
        Objects.requireNonNull(certificationId, "certificationId");
        CertificationContractValues.requireExactIdentity(certificationId.value(), "certificationId", 128);
        Objects.requireNonNull(certificationManifest, "certificationManifest");
        Objects.requireNonNull(candidateId, "candidateId");
        CertificationContractValues.requireExactIdentity(candidateId.value(), "candidateId", 128);
        Objects.requireNonNull(candidateHash, "candidateHash");
        Objects.requireNonNull(sourceManifest, "sourceManifest");
        Objects.requireNonNull(inputLockManifest, "inputLockManifest");
        Objects.requireNonNull(verificationRunId, "verificationRunId");
        CertificationContractValues.requireExactIdentity(verificationRunId.value(), "verificationRunId", 128);
        Objects.requireNonNull(verificationResultManifest, "verificationResultManifest");
        Objects.requireNonNull(compatibilityDescriptor, "compatibilityDescriptor");
        Objects.requireNonNull(certificationEvidence, "certificationEvidence");
        certificationProfile = CertificationContractValues.requireStableId(
                certificationProfile, "certificationProfile", 128);
    }

}
