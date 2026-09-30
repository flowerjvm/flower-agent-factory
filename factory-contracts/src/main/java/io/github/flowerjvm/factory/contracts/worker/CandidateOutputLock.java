package io.github.flowerjvm.factory.contracts.worker;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import java.util.Objects;
import java.util.Optional;

/** Exact immutable candidate/dependency/toolchain output produced by a generation or repair order. */
public record CandidateOutputLock(
        CandidateId candidateId,
        Optional<CandidateId> parentCandidateId,
        ArtifactReference sourceManifestRef,
        ContentHash sourceManifestHash,
        ContentHash candidateHash,
        ArtifactReference dependencyLockRef,
        ContentHash dependencyLockHash,
        ArtifactReference toolchainLockRef,
        ContentHash toolchainLockHash) {

    public CandidateOutputLock {
        Objects.requireNonNull(candidateId, "candidateId");
        parentCandidateId = Objects.requireNonNull(parentCandidateId, "parentCandidateId");
        if (parentCandidateId.filter(candidateId::equals).isPresent()) {
            throw new IllegalArgumentException("a candidate output must not be its own parent");
        }
        Objects.requireNonNull(sourceManifestRef, "sourceManifestRef");
        Objects.requireNonNull(sourceManifestHash, "sourceManifestHash");
        Objects.requireNonNull(candidateHash, "candidateHash");
        Objects.requireNonNull(dependencyLockRef, "dependencyLockRef");
        Objects.requireNonNull(dependencyLockHash, "dependencyLockHash");
        Objects.requireNonNull(toolchainLockRef, "toolchainLockRef");
        Objects.requireNonNull(toolchainLockHash, "toolchainLockHash");
    }
}
