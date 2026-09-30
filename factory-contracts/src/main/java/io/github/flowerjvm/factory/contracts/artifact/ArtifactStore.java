package io.github.flowerjvm.factory.contracts.artifact;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.util.Optional;

/** Storage boundary for immutable, hash-addressed Factory inputs and outputs. */
public interface ArtifactStore {
    ArtifactReference store(Artifact artifact);

    Optional<Artifact> find(TenantId tenantId, ArtifactReference reference);
}
