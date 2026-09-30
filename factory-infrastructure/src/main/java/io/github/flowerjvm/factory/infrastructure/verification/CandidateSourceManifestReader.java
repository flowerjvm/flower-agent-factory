package io.github.flowerjvm.factory.infrastructure.verification;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import java.io.IOException;
import java.util.Objects;

/**
 * Read-only compatibility at the immutable source-manifest boundary. Current Worker v1 manifests
 * use scalar identity/ref/hash fields. Historical PR4 v1 manifests used Jackson record objects
 * ({@code {"value":"..."}} / {@code {"sha256":"..."}}). These are two complete wire forms,
 * not per-field coercions: mixed forms and additional wrapper fields are rejected. No stored bytes,
 * artifact hashes, source hashes or schema versions are rewritten or upgraded by this reader.
 */
public final class CandidateSourceManifestReader {
    private final ObjectMapper mapper;
    private final JacksonWorkerProtocolArtifactDecoder scalarDecoder;

    public CandidateSourceManifestReader(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(16).maxNumberLength(20).maxStringLength(4096).build());
        this.scalarDecoder = new JacksonWorkerProtocolArtifactDecoder(this.mapper);
    }

    /** Callers retain their narrower artifact size, tenant, reference, media type and hash bounds. */
    public CandidateSourceManifest read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0
                || bytes.length > JacksonWorkerProtocolArtifactDecoder.MAX_CANDIDATE_MANIFEST_BYTES) {
            throw invalid();
        }
        JsonNode parsed = mapper.readTree(bytes);
        if (!(parsed instanceof ObjectNode root)
                || !CandidateSourceManifest.SCHEMA_VERSION.equals(root.path("schemaVersion").textValue())) {
            throw invalid();
        }
        if (!root.path("candidateId").isObject()) {
            // The production scalar contract has one authoritative strict decoder, also used by ingest.
            return scalarDecoder.decodeCandidateSourceManifest(bytes);
        }

        // Explicit compatibility for the complete historical record-object v1 form only.
        unwrap(root, "candidateId", "value");
        unwrap(root, "buildSessionId", "value");
        unwrap(root, "candidateHash", "sha256");
        if (!(root.get("files") instanceof ArrayNode files)
                || files.size() > CandidateSourceManifest.MAX_FILE_COUNT) throw invalid();
        for (JsonNode file : files) {
            if (!(file instanceof ObjectNode entry)) throw invalid();
            unwrap(entry, "artifactRef", "value");
            unwrap(entry, "contentHash", "sha256");
        }
        // Outer fields, numeric types, portable paths, bounds and aggregate identities are validated
        // by the same scalar decoder after exact wrapper removal. This copy is never persisted.
        return scalarDecoder.decodeCandidateSourceManifest(mapper.writeValueAsBytes(root));
    }

    private static void unwrap(ObjectNode owner, String field, String wrappedField) {
        if (!(owner.get(field) instanceof ObjectNode wrapper) || wrapper.size() != 1
                || !wrapper.has(wrappedField) || !wrapper.get(wrappedField).isTextual()) throw invalid();
        owner.set(field, wrapper.get(wrappedField));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("CANDIDATE_SOURCE_MANIFEST_WIRE_INVALID");
    }
}
