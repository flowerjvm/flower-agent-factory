package io.github.flowerjvm.factory.infrastructure.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.factory.contracts.worker.CandidateOutputLock;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerResultManifest;
import java.io.IOException;
import java.util.Objects;

/** Canonical scalar JSON encoder paired with {@link JacksonWorkerProtocolArtifactDecoder}. */
public final class JacksonWorkerProtocolArtifactEncoder {
    private final ObjectMapper objectMapper;

    public JacksonWorkerProtocolArtifactEncoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    }

    public byte[] encodeCompletionPayload(CodingWorkerCompletionPayload payload) {
        Objects.requireNonNull(payload, "payload");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", payload.schemaVersion());
        root.put("eventId", payload.eventId());
        root.put("workOrderId", payload.workOrderId().value());
        root.put("workerRunId", payload.workerRunId().value());
        root.put("operationId", payload.operationId());
        root.put("attemptProof", payload.attemptProof());
        root.put("terminalStatus", payload.terminalStatus().name());
        payload.result().ifPresentOrElse(
                result -> root.set("result", result(result)),
                () -> root.putNull("result"));
        root.put("workerCode", payload.workerCode());
        byte[] content;
        try {
            content = objectMapper.writeValueAsBytes(root);
        } catch (IOException exception) {
            throw new IllegalArgumentException("WORKER_PROTOCOL_ARTIFACT_ENCODING_FAILED", exception);
        }
        if (content.length == 0
                || content.length > JacksonWorkerProtocolArtifactDecoder.MAX_COMPLETION_BYTES) {
            throw new IllegalArgumentException("WORKER_PROTOCOL_ARTIFACT_SIZE_INVALID");
        }
        return content;
    }

    private ObjectNode result(CodingWorkerResultManifest manifest) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", manifest.schemaVersion());
        root.put("workOrderId", manifest.workOrderId().value());
        root.put("workerRunId", manifest.workerRunId().value());
        root.put("operationId", manifest.operationId());
        root.put("outputSchemaId", manifest.outputSchemaId());
        root.put("outputSchemaVersion", manifest.outputSchemaVersion());
        root.put("primaryArtifactRef", manifest.primaryArtifactRef().value());
        root.put("primaryResultHash", manifest.primaryResultHash().sha256());
        manifest.transcriptArtifactRef().ifPresentOrElse(
                value -> root.put("transcriptArtifactRef", value.value()),
                () -> root.putNull("transcriptArtifactRef"));
        manifest.transcriptHash().ifPresentOrElse(
                value -> root.put("transcriptHash", value.sha256()),
                () -> root.putNull("transcriptHash"));
        manifest.candidateOutput().ifPresentOrElse(
                value -> root.set("candidateOutput", candidate(value)),
                () -> root.putNull("candidateOutput"));
        root.put("producedAt", manifest.producedAt().toString());
        return root;
    }

    private ObjectNode candidate(CandidateOutputLock lock) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("candidateId", lock.candidateId().value());
        lock.parentCandidateId().ifPresentOrElse(
                value -> root.put("parentCandidateId", value.value()),
                () -> root.putNull("parentCandidateId"));
        root.put("sourceManifestRef", lock.sourceManifestRef().value());
        root.put("sourceManifestHash", lock.sourceManifestHash().sha256());
        root.put("candidateHash", lock.candidateHash().sha256());
        root.put("dependencyLockRef", lock.dependencyLockRef().value());
        root.put("dependencyLockHash", lock.dependencyLockHash().sha256());
        root.put("toolchainLockRef", lock.toolchainLockRef().value());
        root.put("toolchainLockHash", lock.toolchainLockHash().sha256());
        return root;
    }
}
