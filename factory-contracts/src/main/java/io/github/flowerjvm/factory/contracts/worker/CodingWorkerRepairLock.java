package io.github.flowerjvm.factory.contracts.worker;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Additional immutable input lock required for a bounded repair order. */
public record CodingWorkerRepairLock(
        CandidateId baseCandidateId,
        ContentHash baseCandidateHash,
        ArtifactReference findingManifestRef,
        ContentHash findingManifestHash,
        List<String> allowedChangedPaths,
        int repairRound,
        int maxRepairRounds) {

    public CodingWorkerRepairLock {
        Objects.requireNonNull(baseCandidateId, "baseCandidateId");
        Objects.requireNonNull(baseCandidateHash, "baseCandidateHash");
        Objects.requireNonNull(findingManifestRef, "findingManifestRef");
        Objects.requireNonNull(findingManifestHash, "findingManifestHash");
        allowedChangedPaths = List.copyOf(Objects.requireNonNull(allowedChangedPaths, "allowedChangedPaths"));
        if (allowedChangedPaths.isEmpty() || allowedChangedPaths.size() > 256) {
            throw new IllegalArgumentException("allowedChangedPaths must contain 1..256 paths");
        }
        var unique = new HashSet<String>();
        var caseFolded = new HashSet<String>();
        for (String path : allowedChangedPaths) {
            PortableRelativePath.require(path);
            if (!unique.add(path) || !caseFolded.add(PortableRelativePath.caseFold(path))) {
                throw new IllegalArgumentException("allowedChangedPaths must be unique without case collisions");
            }
        }
        if (repairRound < 1 || maxRepairRounds < repairRound) {
            throw new IllegalArgumentException("repair round bounds are invalid");
        }
    }

}
