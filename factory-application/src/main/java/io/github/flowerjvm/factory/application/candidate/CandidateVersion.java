package io.github.flowerjvm.factory.application.candidate;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Insert-only snapshot of one generated candidate and the exact locks used to create it.
 *
 * <p>A caller may create this record only after observing a successful durable WorkerRun. Repair
 * produces another CandidateVersion rather than changing this snapshot.
 */
public record CandidateVersion(
        CandidateId candidateId,
        TenantId tenantId,
        BuildSessionId buildSessionId,
        Optional<CandidateId> parentCandidateId,
        ArtifactReference sourceManifestRef,
        ContentHash sourceHash,
        ArtifactReference dependencyLockRef,
        ContentHash dependencyLockHash,
        ArtifactReference toolchainLockRef,
        ContentHash toolchainLockHash,
        CandidateVersionStatus status,
        WorkOrderId createdByWorkOrderId,
        Instant createdAt) {

    public CandidateVersion {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        parentCandidateId = Objects.requireNonNull(parentCandidateId, "parentCandidateId");
        if (parentCandidateId.filter(candidateId::equals).isPresent()) {
            throw new IllegalArgumentException("a CandidateVersion must not be its own parent");
        }
        Objects.requireNonNull(sourceManifestRef, "sourceManifestRef");
        Objects.requireNonNull(sourceHash, "sourceHash");
        Objects.requireNonNull(dependencyLockRef, "dependencyLockRef");
        Objects.requireNonNull(dependencyLockHash, "dependencyLockHash");
        Objects.requireNonNull(toolchainLockRef, "toolchainLockRef");
        Objects.requireNonNull(toolchainLockHash, "toolchainLockHash");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdByWorkOrderId, "createdByWorkOrderId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
