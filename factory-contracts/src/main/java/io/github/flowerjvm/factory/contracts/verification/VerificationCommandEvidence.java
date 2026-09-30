package io.github.flowerjvm.factory.contracts.verification;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import java.util.List;
import java.util.Objects;

/** Bounded, machine-readable evidence for one verifier-selected command. */
public record VerificationCommandEvidence(
        String commandId,
        List<String> arguments,
        int exitCode,
        int testCount,
        int flowerCheckFindingCount,
        ArtifactReference rawLogRef,
        ContentHash rawLogHash,
        ArtifactReference machineEvidenceRef,
        ContentHash machineEvidenceHash) {

    public VerificationCommandEvidence {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be blank");
        }
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (arguments.isEmpty() || arguments.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("arguments must contain only non-blank values");
        }
        if (testCount < 0 || flowerCheckFindingCount < 0) {
            throw new IllegalArgumentException("evidence counts must not be negative");
        }
        Objects.requireNonNull(rawLogRef, "rawLogRef");
        Objects.requireNonNull(rawLogHash, "rawLogHash");
        Objects.requireNonNull(machineEvidenceRef, "machineEvidenceRef");
        Objects.requireNonNull(machineEvidenceHash, "machineEvidenceHash");
    }
}
