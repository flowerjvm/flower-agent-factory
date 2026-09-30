package io.github.flowerjvm.factory.contracts.verification;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.worker.PortableRelativePath;
import java.util.Objects;

/** One immutable regular file named by a candidate source manifest. */
public record CandidateSourceEntry(
        String path,
        ArtifactReference artifactRef,
        ContentHash contentHash,
        long sizeBytes) {

    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    public CandidateSourceEntry {
        path = PortableRelativePath.require(path);
        Objects.requireNonNull(artifactRef, "artifactRef");
        Objects.requireNonNull(contentHash, "contentHash");
        if (sizeBytes < 0 || sizeBytes > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("sizeBytes exceeds the stable candidate file bound");
        }
    }
}
