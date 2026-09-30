package io.github.flowerjvm.factory.infrastructure.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceEntry;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.worker.WorkerProtocol;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class JacksonWorkerProtocolArtifactDecoderTest {
    private static final String HASH_0 = "0".repeat(64);
    private static final String HASH_1 = "1".repeat(64);
    private static final String HASH_2 = "2".repeat(64);

    private final ObjectMapper mapper = new ObjectMapper();
    private final JacksonWorkerProtocolArtifactDecoder decoder =
            new JacksonWorkerProtocolArtifactDecoder(mapper);

    @Test
    void duplicateJsonFieldsAreRejectedBeforeDomainConstruction() {
        byte[] duplicate = "{\"schemaVersion\":\"a\",\"schemaVersion\":\"b\"}"
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> decoder.decodeCompletionPayload(duplicate));
    }

    @Test
    void canonicalInputManifestWithRepairLockDecodesEveryScalarValueType() throws Exception {
        ObjectNode input = canonicalInputManifest();

        var decoded = decoder.decodeInputManifest(bytes(input));

        assertEquals("work-order-1", decoded.workOrderId().value());
        assertEquals("build-session-1", decoded.buildSessionId().value());
        assertEquals("artifact:skill", decoded.skillArtifactRef().value());
        assertEquals(HASH_0, decoded.skillHash().sha256());
        assertEquals(List.of("src/Main.java"), decoded.repairLock().orElseThrow().allowedChangedPaths());
        assertEquals(new CandidateId("candidate-base"), decoded.repairLock().orElseThrow().baseCandidateId());
    }

    @Test
    void successfulDesignCompletionDecodesPrimaryOnlyResult() throws Exception {
        ObjectNode completion = successfulCompletion(false);

        var decoded = decoder.decodeCompletionPayload(bytes(completion));

        assertEquals(WorkerRunStatus.SUCCEEDED, decoded.terminalStatus());
        assertTrue(decoded.result().isPresent());
        assertTrue(decoded.result().orElseThrow().candidateOutput().isEmpty());
        assertTrue(decoded.result().orElseThrow().transcriptArtifactRef().isEmpty());
        assertEquals("agent-blueprint", decoded.result().orElseThrow().outputSchemaId());
    }

    @Test
    void successfulGenerateCompletionDecodesCandidateAndTranscriptLocks() throws Exception {
        ObjectNode completion = successfulCompletion(true);

        var decoded = decoder.decodeCompletionPayload(bytes(completion));

        var result = decoded.result().orElseThrow();
        var candidate = result.candidateOutput().orElseThrow();
        assertEquals(new CandidateId("candidate-new"), candidate.candidateId());
        assertEquals(new CandidateId("candidate-parent"), candidate.parentCandidateId().orElseThrow());
        assertEquals(new ArtifactReference("artifact:source-manifest"), candidate.sourceManifestRef());
        assertEquals(new ContentHash(HASH_0), candidate.candidateHash());
        assertEquals(new ArtifactReference("artifact:transcript"), result.transcriptArtifactRef().orElseThrow());
        assertEquals(new ContentHash(HASH_2), result.transcriptHash().orElseThrow());
    }

    @Test
    void canonicalEncoderRoundTripsDesignAndGenerateCompletions() throws Exception {
        var encoder = new JacksonWorkerProtocolArtifactEncoder(mapper);
        var design = decoder.decodeCompletionPayload(bytes(successfulCompletion(false)));
        var generate = decoder.decodeCompletionPayload(bytes(successfulCompletion(true)));

        assertEquals(design, decoder.decodeCompletionPayload(encoder.encodeCompletionPayload(design)));
        assertEquals(generate, decoder.decodeCompletionPayload(encoder.encodeCompletionPayload(generate)));
    }

    @Test
    void failedCompletionRequiresAndDecodesExplicitNullResult() throws Exception {
        ObjectNode completion = mapper.createObjectNode();
        completion.put("schemaVersion", WorkerProtocol.COMPLETION_SCHEMA_VERSION);
        completion.put("eventId", "event-failed-1");
        completion.put("workOrderId", "work-order-1");
        completion.put("workerRunId", "worker-run-1");
        completion.put("operationId", "operation-1");
        completion.put("attemptProof", HASH_1);
        completion.put("terminalStatus", "FAILED");
        completion.putNull("result");
        completion.put("workerCode", "WORKER_EXECUTION_FAILED");

        var decoded = decoder.decodeCompletionPayload(bytes(completion));

        assertEquals(WorkerRunStatus.FAILED, decoded.terminalStatus());
        assertTrue(decoded.result().isEmpty());
    }

    @Test
    void validCandidateManifestIsAcceptedButTrailingJsonIsRejected() throws Exception {
        ObjectNode source = canonicalCandidateSourceManifest();
        CandidateSourceManifest expected = new CandidateSourceManifest(
                CandidateSourceManifest.SCHEMA_VERSION,
                new CandidateId("candidate-1"),
                new BuildSessionId("session-1"),
                CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                new ContentHash(HASH_0),
                1,
                3,
                List.of(new CandidateSourceEntry(
                        "src/Main.java",
                        new ArtifactReference("artifact:source"),
                        new ContentHash(HASH_1),
                        3)));
        byte[] valid = bytes(source);
        byte[] trailing = (new String(valid, StandardCharsets.UTF_8) + " {}")
                .getBytes(StandardCharsets.UTF_8);

        assertEquals(expected, decoder.decodeCandidateSourceManifest(valid));
        assertThrows(IllegalArgumentException.class, () -> decoder.decodeCandidateSourceManifest(trailing));
    }

    @Test
    void unknownNestedFieldAndNestedRecordShapesAreRejected() throws Exception {
        ObjectNode completion = successfulCompletion(true);
        ((ObjectNode) completion.get("result").get("candidateOutput")).put("rawCredential", "forbidden");
        ObjectNode nestedId = successfulCompletion(false);
        ((ObjectNode) nestedId).set("workOrderId", mapper.createObjectNode().put("value", "work-order-1"));

        assertThrows(IllegalArgumentException.class, () -> decoder.decodeCompletionPayload(bytes(completion)));
        assertThrows(IllegalArgumentException.class, () -> decoder.decodeCompletionPayload(bytes(nestedId)));
    }

    @Test
    void listCardinalityIsRejectedBeforeRepairDomainConstruction() throws Exception {
        ObjectNode input = canonicalInputManifest();
        var paths = ((ObjectNode) input.get("repairLock")).withArray("allowedChangedPaths");
        paths.removeAll();
        for (int index = 0; index < 257; index++) {
            paths.add("src/File" + index + ".java");
        }

        assertThrows(IllegalArgumentException.class, () -> decoder.decodeInputManifest(bytes(input)));
    }

    @Test
    void callbackSizeIsRejectedBeforeJacksonAllocatesItsObjectGraph() {
        byte[] oversized = new byte[JacksonWorkerProtocolArtifactDecoder.MAX_COMPLETION_BYTES + 1];

        assertThrows(IllegalArgumentException.class, () -> decoder.decodeCompletionPayload(oversized));
    }

    private ObjectNode canonicalInputManifest() {
        ObjectNode input = mapper.createObjectNode();
        input.put("schemaVersion", "factory.coding-worker-input-manifest.v1");
        input.put("workOrderId", "work-order-1");
        input.put("buildSessionId", "build-session-1");
        input.put("skillId", "java-factory");
        input.put("skillVersion", "1.0.0");
        input.put("skillArtifactRef", "artifact:skill");
        input.put("skillHash", HASH_0);
        input.put("dependencyLockRef", "artifact:dependency-lock");
        input.put("dependencyLockHash", HASH_0);
        input.put("toolchainLockRef", "artifact:toolchain-lock");
        input.put("toolchainLockHash", HASH_0);
        input.put("apiSignatureIndexRef", "artifact:api-index");
        input.put("apiSignatureIndexHash", HASH_0);
        input.put("productContractBundleRef", "artifact:product-contract");
        input.put("productContractBundleHash", HASH_0);
        input.put("gateProfile", "default");
        input.put("requirementTestMatrixRef", "artifact:test-matrix");
        input.put("requirementTestMatrixHash", HASH_0);
        input.put("sourceLockAlgorithmId", CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID);
        ObjectNode repair = input.putObject("repairLock");
        repair.put("baseCandidateId", "candidate-base");
        repair.put("baseCandidateHash", HASH_1);
        repair.put("findingManifestRef", "artifact:finding-manifest");
        repair.put("findingManifestHash", HASH_2);
        repair.putArray("allowedChangedPaths").add("src/Main.java");
        repair.put("repairRound", 1);
        repair.put("maxRepairRounds", 2);
        return input;
    }

    private ObjectNode canonicalCandidateSourceManifest() {
        ObjectNode source = mapper.createObjectNode();
        source.put("schemaVersion", CandidateSourceManifest.SCHEMA_VERSION);
        source.put("candidateId", "candidate-1");
        source.put("buildSessionId", "session-1");
        source.put("sourceLockAlgorithmId", CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID);
        source.put("candidateHash", HASH_0);
        source.put("fileCount", 1);
        source.put("totalBytes", 3);
        ObjectNode entry = source.putArray("files").addObject();
        entry.put("path", "src/Main.java");
        entry.put("artifactRef", "artifact:source");
        entry.put("contentHash", HASH_1);
        entry.put("sizeBytes", 3);
        return source;
    }

    private ObjectNode successfulCompletion(boolean generation) {
        ObjectNode completion = mapper.createObjectNode();
        completion.put("schemaVersion", WorkerProtocol.COMPLETION_SCHEMA_VERSION);
        completion.put("eventId", generation ? "event-generate-1" : "event-design-1");
        completion.put("workOrderId", "work-order-1");
        completion.put("workerRunId", "worker-run-1");
        completion.put("operationId", "operation-1");
        completion.put("attemptProof", HASH_1);
        completion.put("terminalStatus", "SUCCEEDED");
        completion.put("workerCode", "WORKER_SUCCEEDED");
        ObjectNode result = completion.putObject("result");
        result.put("schemaVersion", WorkerProtocol.RESULT_SCHEMA_VERSION);
        result.put("workOrderId", "work-order-1");
        result.put("workerRunId", "worker-run-1");
        result.put("operationId", "operation-1");
        result.put("outputSchemaId", generation ? "candidate-source" : "agent-blueprint");
        result.put("outputSchemaVersion", "1.0.0");
        result.put("primaryArtifactRef", generation ? "artifact:source-manifest" : "artifact:blueprint");
        result.put("primaryResultHash", HASH_0);
        if (generation) {
            result.put("transcriptArtifactRef", "artifact:transcript");
            result.put("transcriptHash", HASH_2);
            ObjectNode candidate = result.putObject("candidateOutput");
            candidate.put("candidateId", "candidate-new");
            candidate.put("parentCandidateId", "candidate-parent");
            candidate.put("sourceManifestRef", "artifact:source-manifest");
            candidate.put("sourceManifestHash", HASH_1);
            candidate.put("candidateHash", HASH_0);
            candidate.put("dependencyLockRef", "artifact:dependency-lock");
            candidate.put("dependencyLockHash", HASH_1);
            candidate.put("toolchainLockRef", "artifact:toolchain-lock");
            candidate.put("toolchainLockHash", HASH_2);
        } else {
            result.putNull("transcriptArtifactRef");
            result.putNull("transcriptHash");
            result.putNull("candidateOutput");
        }
        result.put("producedAt", "2026-08-20T12:00:00Z");
        return completion;
    }

    private byte[] bytes(JsonNode value) throws Exception {
        return mapper.writeValueAsBytes(value);
    }
}
