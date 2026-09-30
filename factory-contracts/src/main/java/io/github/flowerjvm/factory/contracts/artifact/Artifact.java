package io.github.flowerjvm.factory.contracts.artifact;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.util.Objects;

/** Immutable artifact materialization passed across the artifact-store boundary. */
public record Artifact(
        TenantId tenantId,
        ArtifactReference reference,
        ContentHash contentHash,
        String mediaType,
        byte[] content) {

    public Artifact {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(contentHash, "contentHash");
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType must not be blank");
        }
        content = Objects.requireNonNull(content, "content").clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
