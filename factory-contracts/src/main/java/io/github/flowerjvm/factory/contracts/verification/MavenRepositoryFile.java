package io.github.flowerjvm.factory.contracts.verification;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import java.util.Objects;

/** One immutable file allowed in a verification run's curated Maven repository. */
public record MavenRepositoryFile(
        String path,
        ArtifactReference artifactRef,
        ContentHash contentHash,
        long sizeBytes) {

    public MavenRepositoryFile {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Objects.requireNonNull(artifactRef, "artifactRef");
        Objects.requireNonNull(contentHash, "contentHash");
        if (sizeBytes < 1) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
    }
}
