package io.github.flowerjvm.factory.infrastructure.worker;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifactDecoder;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceEntry;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.worker.CandidateOutputLock;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerRepairLock;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerResultManifest;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Strict, bounded decoder for immutable Coding Worker protocol artifacts. */
public final class JacksonWorkerProtocolArtifactDecoder implements WorkerProtocolArtifactDecoder {
    public static final int MAX_COMPLETION_BYTES = 64 * 1024;
    public static final int MAX_INPUT_MANIFEST_BYTES = 4 * 1024 * 1024;
    public static final int MAX_CANDIDATE_MANIFEST_BYTES = 16 * 1024 * 1024;

    private static final int MAX_JSON_STRING_LENGTH = 4 * 1024;
    private static final Set<String> COMPLETION_FIELDS = Set.of(
            "schemaVersion", "eventId", "workOrderId", "workerRunId", "operationId",
            "attemptProof", "terminalStatus", "result", "workerCode");
    private static final Set<String> RESULT_FIELDS = Set.of(
            "schemaVersion", "workOrderId", "workerRunId", "operationId", "outputSchemaId",
            "outputSchemaVersion", "primaryArtifactRef", "primaryResultHash",
            "transcriptArtifactRef", "transcriptHash", "candidateOutput", "producedAt");
    private static final Set<String> CANDIDATE_OUTPUT_FIELDS = Set.of(
            "candidateId", "parentCandidateId", "sourceManifestRef", "sourceManifestHash",
            "candidateHash", "dependencyLockRef", "dependencyLockHash", "toolchainLockRef",
            "toolchainLockHash");
    private static final Set<String> INPUT_FIELDS = Set.of(
            "schemaVersion", "workOrderId", "buildSessionId", "skillId", "skillVersion",
            "skillArtifactRef", "skillHash", "dependencyLockRef", "dependencyLockHash",
            "toolchainLockRef", "toolchainLockHash", "apiSignatureIndexRef",
            "apiSignatureIndexHash", "productContractBundleRef", "productContractBundleHash",
            "gateProfile", "requirementTestMatrixRef", "requirementTestMatrixHash",
            "sourceLockAlgorithmId", "repairLock");
    private static final Set<String> REPAIR_FIELDS = Set.of(
            "baseCandidateId", "baseCandidateHash", "findingManifestRef", "findingManifestHash",
            "allowedChangedPaths", "repairRound", "maxRepairRounds");
    private static final Set<String> SOURCE_FIELDS = Set.of(
            "schemaVersion", "candidateId", "buildSessionId", "sourceLockAlgorithmId",
            "candidateHash", "fileCount", "totalBytes", "files");
    private static final Set<String> SOURCE_ENTRY_FIELDS = Set.of(
            "path", "artifactRef", "contentHash", "sizeBytes");

    private final ObjectMapper objectMapper;

    public JacksonWorkerProtocolArtifactDecoder(ObjectMapper objectMapper) {
        ObjectMapper strict = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        strict.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        strict.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        strict.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        strict.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(16)
                .maxNumberLength(20)
                .maxStringLength(MAX_JSON_STRING_LENGTH)
                .build());
        this.objectMapper = strict;
    }

    @Override
    public CodingWorkerCompletionPayload decodeCompletionPayload(byte[] content) {
        return decode(content, MAX_COMPLETION_BYTES, this::completion);
    }

    @Override
    public CodingWorkerInputManifest decodeInputManifest(byte[] content) {
        return decode(content, MAX_INPUT_MANIFEST_BYTES, this::inputManifest);
    }

    @Override
    public CandidateSourceManifest decodeCandidateSourceManifest(byte[] content) {
        return decode(content, MAX_CANDIDATE_MANIFEST_BYTES, this::candidateSourceManifest);
    }

    private CodingWorkerCompletionPayload completion(ObjectNode root) {
        exactFields(root, COMPLETION_FIELDS);
        JsonNode resultNode = root.get("result");
        Optional<CodingWorkerResultManifest> result = resultNode.isNull()
                ? Optional.empty()
                : Optional.of(resultManifest(requireObject(resultNode)));
        return new CodingWorkerCompletionPayload(
                text(root, "schemaVersion", 128),
                text(root, "eventId", 128),
                new WorkOrderId(text(root, "workOrderId", 256)),
                new WorkerRunId(text(root, "workerRunId", 256)),
                text(root, "operationId", 256),
                lowerHex(root, "attemptProof"),
                enumValue(root, "terminalStatus", WorkerRunStatus.class),
                result,
                text(root, "workerCode", 128));
    }

    private CodingWorkerResultManifest resultManifest(ObjectNode root) {
        exactFields(root, RESULT_FIELDS);
        Optional<ArtifactReference> transcriptReference = optionalReference(root, "transcriptArtifactRef");
        Optional<ContentHash> transcriptHash = optionalHash(root, "transcriptHash");
        JsonNode candidateNode = root.get("candidateOutput");
        Optional<CandidateOutputLock> candidateOutput = candidateNode.isNull()
                ? Optional.empty()
                : Optional.of(candidateOutput(requireObject(candidateNode)));
        String producedAt = text(root, "producedAt", 64);
        Instant produced = Instant.parse(producedAt);
        if (!produced.toString().equals(producedAt)) {
            throw invalid();
        }
        return new CodingWorkerResultManifest(
                text(root, "schemaVersion", 128),
                new WorkOrderId(text(root, "workOrderId", 256)),
                new WorkerRunId(text(root, "workerRunId", 256)),
                text(root, "operationId", 256),
                text(root, "outputSchemaId", 128),
                text(root, "outputSchemaVersion", 64),
                reference(root, "primaryArtifactRef"),
                hash(root, "primaryResultHash"),
                transcriptReference,
                transcriptHash,
                candidateOutput,
                produced);
    }

    private CandidateOutputLock candidateOutput(ObjectNode root) {
        exactFields(root, CANDIDATE_OUTPUT_FIELDS);
        JsonNode parentNode = root.get("parentCandidateId");
        Optional<CandidateId> parent = parentNode.isNull()
                ? Optional.empty()
                : Optional.of(new CandidateId(textNode(parentNode, 256)));
        return new CandidateOutputLock(
                new CandidateId(text(root, "candidateId", 256)),
                parent,
                reference(root, "sourceManifestRef"),
                hash(root, "sourceManifestHash"),
                hash(root, "candidateHash"),
                reference(root, "dependencyLockRef"),
                hash(root, "dependencyLockHash"),
                reference(root, "toolchainLockRef"),
                hash(root, "toolchainLockHash"));
    }

    private CodingWorkerInputManifest inputManifest(ObjectNode root) {
        exactFields(root, INPUT_FIELDS);
        JsonNode repairNode = root.get("repairLock");
        Optional<CodingWorkerRepairLock> repair = repairNode.isNull()
                ? Optional.empty()
                : Optional.of(repairLock(requireObject(repairNode)));
        return new CodingWorkerInputManifest(
                text(root, "schemaVersion", 128),
                new WorkOrderId(text(root, "workOrderId", 256)),
                new BuildSessionId(text(root, "buildSessionId", 256)),
                text(root, "skillId", 128),
                text(root, "skillVersion", 64),
                reference(root, "skillArtifactRef"),
                hash(root, "skillHash"),
                reference(root, "dependencyLockRef"),
                hash(root, "dependencyLockHash"),
                reference(root, "toolchainLockRef"),
                hash(root, "toolchainLockHash"),
                reference(root, "apiSignatureIndexRef"),
                hash(root, "apiSignatureIndexHash"),
                reference(root, "productContractBundleRef"),
                hash(root, "productContractBundleHash"),
                text(root, "gateProfile", 128),
                reference(root, "requirementTestMatrixRef"),
                hash(root, "requirementTestMatrixHash"),
                text(root, "sourceLockAlgorithmId", 128),
                repair);
    }

    private CodingWorkerRepairLock repairLock(ObjectNode root) {
        exactFields(root, REPAIR_FIELDS);
        ArrayNode paths = array(root, "allowedChangedPaths", 256);
        if (paths.isEmpty()) {
            throw invalid();
        }
        var allowed = new ArrayList<String>(paths.size());
        paths.forEach(path -> allowed.add(textNode(path, 1024)));
        return new CodingWorkerRepairLock(
                new CandidateId(text(root, "baseCandidateId", 256)),
                hash(root, "baseCandidateHash"),
                reference(root, "findingManifestRef"),
                hash(root, "findingManifestHash"),
                List.copyOf(allowed),
                integer(root, "repairRound"),
                integer(root, "maxRepairRounds"));
    }

    private CandidateSourceManifest candidateSourceManifest(ObjectNode root) {
        exactFields(root, SOURCE_FIELDS);
        ArrayNode files = array(root, "files", CandidateSourceManifest.MAX_FILE_COUNT);
        var entries = new ArrayList<CandidateSourceEntry>(files.size());
        files.forEach(node -> entries.add(candidateSourceEntry(requireObject(node))));
        return new CandidateSourceManifest(
                text(root, "schemaVersion", 128),
                new CandidateId(text(root, "candidateId", 256)),
                new BuildSessionId(text(root, "buildSessionId", 256)),
                text(root, "sourceLockAlgorithmId", 128),
                hash(root, "candidateHash"),
                integer(root, "fileCount"),
                longInteger(root, "totalBytes"),
                List.copyOf(entries));
    }

    private CandidateSourceEntry candidateSourceEntry(ObjectNode root) {
        exactFields(root, SOURCE_ENTRY_FIELDS);
        return new CandidateSourceEntry(
                text(root, "path", 1024),
                reference(root, "artifactRef"),
                hash(root, "contentHash"),
                longInteger(root, "sizeBytes"));
    }

    private <T> T decode(byte[] content, int maximumBytes, Decoder<T> decoder) {
        if (content == null || content.length == 0 || content.length > maximumBytes) {
            throw new IllegalArgumentException("WORKER_PROTOCOL_ARTIFACT_SIZE_INVALID");
        }
        try {
            return decoder.decode(requireObject(objectMapper.readTree(content)));
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalArgumentException("WORKER_PROTOCOL_ARTIFACT_INVALID", invalid);
        }
    }

    private static ObjectNode requireObject(JsonNode value) {
        if (!(value instanceof ObjectNode object)) {
            throw invalid();
        }
        return object;
    }

    private static void exactFields(ObjectNode object, Set<String> expected) {
        var actual = new HashSet<String>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalid();
        }
    }

    private static String text(ObjectNode object, String field, int maximum) {
        return textNode(object.get(field), maximum);
    }

    private static String textNode(JsonNode value, int maximum) {
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        String text = value.textValue();
        if (text.isBlank() || text.length() > maximum
                || text.chars().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return text;
    }

    private static ArtifactReference reference(ObjectNode object, String field) {
        return new ArtifactReference(text(object, field, 512));
    }

    private static Optional<ArtifactReference> optionalReference(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        return value.isNull()
                ? Optional.empty()
                : Optional.of(new ArtifactReference(textNode(value, 512)));
    }

    private static ContentHash hash(ObjectNode object, String field) {
        return new ContentHash(lowerHex(object, field));
    }

    private static Optional<ContentHash> optionalHash(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        return value.isNull()
                ? Optional.empty()
                : Optional.of(new ContentHash(lowerHexNode(value)));
    }

    private static String lowerHex(ObjectNode object, String field) {
        return lowerHexNode(object.get(field));
    }

    private static String lowerHexNode(JsonNode value) {
        String hash = textNode(value, 64);
        if (hash.length() != 64 || !hash.matches("[0-9a-f]{64}")) {
            throw invalid();
        }
        return hash;
    }

    private static int integer(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid();
        }
        return value.intValue();
    }

    private static long longInteger(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid();
        }
        return value.longValue();
    }

    private static ArrayNode array(ObjectNode object, String field, int maximumSize) {
        JsonNode value = object.get(field);
        if (!(value instanceof ArrayNode array) || array.size() > maximumSize) {
            throw invalid();
        }
        return array;
    }

    private static <E extends Enum<E>> E enumValue(
            ObjectNode object, String field, Class<E> enumType) {
        return Enum.valueOf(enumType, text(object, field, 64));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid Coding Worker protocol JSON");
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T decode(ObjectNode root);
    }
}
