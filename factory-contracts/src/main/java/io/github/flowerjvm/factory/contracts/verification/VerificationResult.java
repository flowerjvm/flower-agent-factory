package io.github.flowerjvm.factory.contracts.verification;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import java.util.List;
import java.util.Objects;

/** Machine-readable verification outcome and immutable evidence references. */
public record VerificationResult(
        VerificationStatus status,
        VerificationDisposition disposition,
        List<String> stableCodes,
        ArtifactReference resultManifestRef,
        ContentHash resultManifestHash,
        List<ArtifactReference> evidenceArtifacts) {
    public VerificationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(disposition, "disposition");
        stableCodes = List.copyOf(Objects.requireNonNull(stableCodes, "stableCodes"));
        if (stableCodes.isEmpty() || stableCodes.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("stableCodes must contain only non-blank values");
        }
        Objects.requireNonNull(resultManifestRef, "resultManifestRef");
        Objects.requireNonNull(resultManifestHash, "resultManifestHash");
        evidenceArtifacts = List.copyOf(Objects.requireNonNull(evidenceArtifacts, "evidenceArtifacts"));
        if (status == VerificationStatus.PASSED && disposition != VerificationDisposition.REVIEW_ELIGIBLE) {
            throw new IllegalArgumentException("PASSED verification must be review eligible");
        }
        if (status == VerificationStatus.FAILED && disposition == VerificationDisposition.REVIEW_ELIGIBLE) {
            throw new IllegalArgumentException("FAILED verification must not be review eligible");
        }
    }
}
