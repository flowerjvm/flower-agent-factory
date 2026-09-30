package io.github.flowerjvm.factory.contracts.verification;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import java.util.List;
import java.util.Objects;

/** Immutable result lock proving which candidate and deterministic gates were independently run. */
public record VerificationResultManifest(
        String schemaVersion,
        VerificationRunId verificationRunId,
        BuildSessionId buildSessionId,
        CandidateId candidateId,
        ArtifactReference candidateManifestRef,
        ContentHash candidateHash,
        ArtifactReference dependencyLockRef,
        ContentHash dependencyLockHash,
        ContentHash sourceHashBefore,
        ContentHash sourceHashAfter,
        String gateProfile,
        ArtifactReference toolchainLockRef,
        ContentHash toolchainLockHash,
        ArtifactReference fixtureSetRef,
        ContentHash fixtureSetHash,
        VerificationFixtureSelfTestEvidence fixtureSelfTest,
        VerificationSandboxEvidence sandbox,
        VerificationStatus status,
        VerificationDisposition disposition,
        List<String> stableCodes,
        List<VerificationCommandEvidence> commands,
        List<ArtifactReference> evidenceArtifacts) {

    public static final String SCHEMA_VERSION = "factory.verification-result-manifest.v1";

    public VerificationResultManifest {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported verification result manifest schemaVersion");
        }
        Objects.requireNonNull(verificationRunId, "verificationRunId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(candidateManifestRef, "candidateManifestRef");
        Objects.requireNonNull(candidateHash, "candidateHash");
        Objects.requireNonNull(dependencyLockRef, "dependencyLockRef");
        Objects.requireNonNull(dependencyLockHash, "dependencyLockHash");
        Objects.requireNonNull(sourceHashBefore, "sourceHashBefore");
        Objects.requireNonNull(sourceHashAfter, "sourceHashAfter");
        if (gateProfile == null || gateProfile.isBlank()) {
            throw new IllegalArgumentException("gateProfile must not be blank");
        }
        Objects.requireNonNull(toolchainLockRef, "toolchainLockRef");
        Objects.requireNonNull(toolchainLockHash, "toolchainLockHash");
        Objects.requireNonNull(fixtureSetRef, "fixtureSetRef");
        Objects.requireNonNull(fixtureSetHash, "fixtureSetHash");
        Objects.requireNonNull(sandbox, "sandbox");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(disposition, "disposition");
        stableCodes = List.copyOf(Objects.requireNonNull(stableCodes, "stableCodes"));
        if (stableCodes.isEmpty() || stableCodes.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("stableCodes must contain only non-blank values");
        }
        commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
        evidenceArtifacts = List.copyOf(Objects.requireNonNull(evidenceArtifacts, "evidenceArtifacts"));
        if (status == VerificationStatus.PASSED && disposition != VerificationDisposition.REVIEW_ELIGIBLE) {
            throw new IllegalArgumentException("PASSED verification must be review eligible");
        }
        if (status == VerificationStatus.PASSED && fixtureSelfTest == null) {
            throw new IllegalArgumentException("PASSED verification requires fixture self-test evidence");
        }
        if (status == VerificationStatus.PASSED
                && (!sandbox.networkDisabled()
                        || !sandbox.readOnlyRootFilesystem()
                        || !sandbox.nonRoot())) {
            throw new IllegalArgumentException("PASSED verification requires the fail-closed sandbox policy");
        }
        if (status == VerificationStatus.FAILED && disposition == VerificationDisposition.REVIEW_ELIGIBLE) {
            throw new IllegalArgumentException("FAILED verification must not be review eligible");
        }
    }
}
