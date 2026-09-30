package io.github.flowerjvm.factory.contracts.certification;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import java.util.Objects;

/** Exact immutable artifact reference and SHA-256 pair used by certification and consumers. */
public record CertificationArtifactLock(ArtifactReference reference, ContentHash hash) {
    public CertificationArtifactLock {
        reference = CertificationContractValues.requireExactReference(reference, "reference");
        Objects.requireNonNull(hash, "hash");
    }
}

