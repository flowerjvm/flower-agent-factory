package io.github.flowerjvm.factory.contracts.certification;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import java.time.Instant;
import java.util.Objects;

/** Canonical manifest of the currently supported certified AGENT_PACK component. */
public record CertifiedAgentComponentManifest(
        String schemaVersion,
        TenantId tenantId,
        ProductLineId productLineId,
        CertifiedArtifactType artifactType,
        CertificationId certificationId,
        CandidateId candidateId,
        ContentHash candidateHash,
        CertificationArtifactLock sourceManifest,
        CertificationArtifactLock inputLockManifest,
        VerificationRunId verificationRunId,
        CertificationArtifactLock verificationResultManifest,
        CertificationArtifactLock compatibilityDescriptor,
        CertificationArtifactLock certificationEvidence,
        String certificationProfile,
        String factoryVersion,
        Instant issuedAt,
        Instant expiresAt,
        String status) {

    public static final String SCHEMA_VERSION = "factory.certified-agent-component-manifest.v1";
    public static final String CERTIFIED_STATUS = "CERTIFIED";

    public CertifiedAgentComponentManifest {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported Certified Agent Component schemaVersion");
        }
        Objects.requireNonNull(tenantId, "tenantId");
        productLineId = CertificationContractValues.requireAgentPackLine(productLineId);
        artifactType = CertificationContractValues.requireAgentPackType(artifactType);
        Objects.requireNonNull(certificationId, "certificationId");
        CertificationContractValues.requireExactIdentity(certificationId.value(), "certificationId", 128);
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(candidateHash, "candidateHash");
        Objects.requireNonNull(sourceManifest, "sourceManifest");
        Objects.requireNonNull(inputLockManifest, "inputLockManifest");
        Objects.requireNonNull(verificationRunId, "verificationRunId");
        Objects.requireNonNull(verificationResultManifest, "verificationResultManifest");
        Objects.requireNonNull(compatibilityDescriptor, "compatibilityDescriptor");
        Objects.requireNonNull(certificationEvidence, "certificationEvidence");
        certificationProfile = CertificationContractValues.requireStableId(
                certificationProfile, "certificationProfile", 128);
        factoryVersion = CertificationContractValues.requireResolvedVersion(factoryVersion, "factoryVersion");
        issuedAt = CertificationContractValues.requireCanonicalInstant(issuedAt, "issuedAt");
        Instant canonicalIssuedAt = issuedAt;
        if (expiresAt != null) {
            expiresAt = CertificationContractValues.requireCanonicalInstant(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(canonicalIssuedAt)) {
                throw new IllegalArgumentException("expiresAt must be after issuedAt");
            }
        }
        if (!CERTIFIED_STATUS.equals(status)) {
            throw new IllegalArgumentException("Certified Agent Component status must be CERTIFIED");
        }
    }

}
