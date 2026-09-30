package io.github.flowerjvm.factory.contracts.worker;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Strict v1, tokenless result manifest returned by a Coding Worker. */
public record CodingWorkerResultManifest(
        String schemaVersion,
        WorkOrderId workOrderId,
        WorkerRunId workerRunId,
        String operationId,
        String outputSchemaId,
        String outputSchemaVersion,
        ArtifactReference primaryArtifactRef,
        ContentHash primaryResultHash,
        Optional<ArtifactReference> transcriptArtifactRef,
        Optional<ContentHash> transcriptHash,
        Optional<CandidateOutputLock> candidateOutput,
        Instant producedAt) {

    public CodingWorkerResultManifest {
        if (!WorkerProtocol.RESULT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported Coding Worker result schemaVersion");
        }
        Objects.requireNonNull(workOrderId, "workOrderId");
        Objects.requireNonNull(workerRunId, "workerRunId");
        operationId = requireText(operationId, "operationId", 256);
        outputSchemaId = requireText(outputSchemaId, "outputSchemaId", 128);
        outputSchemaVersion = requireText(outputSchemaVersion, "outputSchemaVersion", 64);
        Objects.requireNonNull(primaryArtifactRef, "primaryArtifactRef");
        Objects.requireNonNull(primaryResultHash, "primaryResultHash");
        transcriptArtifactRef = Objects.requireNonNull(transcriptArtifactRef, "transcriptArtifactRef");
        transcriptHash = Objects.requireNonNull(transcriptHash, "transcriptHash");
        if (transcriptArtifactRef.isPresent() != transcriptHash.isPresent()) {
            throw new IllegalArgumentException("transcript reference and hash must be present together");
        }
        candidateOutput = Objects.requireNonNull(candidateOutput, "candidateOutput");
        candidateOutput.ifPresent(lock -> {
            if (!lock.sourceManifestRef().equals(primaryArtifactRef)
                    || !lock.candidateHash().equals(primaryResultHash)) {
                throw new IllegalArgumentException(
                        "candidate source manifest and candidate hash must be the primary result");
            }
        });
        Objects.requireNonNull(producedAt, "producedAt");
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
        }
        return value;
    }
}
