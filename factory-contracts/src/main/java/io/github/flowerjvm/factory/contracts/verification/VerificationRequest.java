package io.github.flowerjvm.factory.contracts.verification;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import java.util.Objects;

/** Locked candidate input for an independent deterministic verification gate. */
public record VerificationRequest(
        TenantId tenantId,
        VerificationRunId verificationRunId,
        BuildSessionId buildSessionId,
        CandidateId candidateId,
        ArtifactReference candidateManifest,
        ContentHash candidateHash,
        ArtifactReference dependencyLockRef,
        ContentHash dependencyLockHash,
        ArtifactReference toolchainLockRef,
        String gateProfile,
        ContentHash toolchainLockHash,
        ArtifactReference fixtureSetRef,
        ContentHash fixtureSetHash) {
    public VerificationRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(verificationRunId, "verificationRunId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(candidateManifest, "candidateManifest");
        Objects.requireNonNull(candidateHash, "candidateHash");
        Objects.requireNonNull(dependencyLockRef, "dependencyLockRef");
        Objects.requireNonNull(dependencyLockHash, "dependencyLockHash");
        Objects.requireNonNull(toolchainLockRef, "toolchainLockRef");
        if (gateProfile == null || gateProfile.isBlank()) {
            throw new IllegalArgumentException("gateProfile must not be blank");
        }
        Objects.requireNonNull(toolchainLockHash, "toolchainLockHash");
        Objects.requireNonNull(fixtureSetRef, "fixtureSetRef");
        Objects.requireNonNull(fixtureSetHash, "fixtureSetHash");
    }
}
