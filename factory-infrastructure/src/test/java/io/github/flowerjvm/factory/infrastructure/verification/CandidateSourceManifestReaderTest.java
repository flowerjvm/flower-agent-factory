package io.github.flowerjvm.factory.infrastructure.verification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceEntry;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandidateSourceManifestReaderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CandidateSourceManifestReader reader = new CandidateSourceManifestReader(mapper);
    private final CandidateSourceManifest manifest = manifest();

    @Test
    void productionScalarV1UsesTheSameStrictDecoderAsCandidateIngestion() throws Exception {
        byte[] bytes = wire(false);
        ObjectNode json = (ObjectNode) mapper.readTree(bytes);
        assertTrue(json.get("candidateId").isTextual());
        assertTrue(json.get("buildSessionId").isTextual());
        assertTrue(json.get("candidateHash").isTextual());
        assertTrue(json.get("files").get(0).get("artifactRef").isTextual());
        assertTrue(json.get("files").get(0).get("contentHash").isTextual());
        assertEquals(new JacksonWorkerProtocolArtifactDecoder(mapper).decodeCandidateSourceManifest(bytes),
                reader.read(bytes));
        assertEquals(manifest, reader.read(bytes));
    }

    @Test
    void completeHistoricalRecordObjectV1RemainsReadableWithoutRewritingItsBytesOrHash() throws Exception {
        byte[] legacy = wire(true);
        byte[] original = legacy.clone();
        var originalHash = new OrdinalSourceTreeHasher().sha256(legacy);
        assertTrue(mapper.readTree(legacy).get("candidateId").isObject());
        assertEquals(manifest, reader.read(legacy));
        assertEquals(reader.read(wire(false)), reader.read(legacy));
        assertArrayEquals(original, legacy);
        assertEquals(originalHash, new OrdinalSourceTreeHasher().sha256(legacy));
    }

    @Test
    void unknownVersionCannotOptIntoEitherCompatibilityForm() throws Exception {
        for (boolean legacy : List.of(false, true)) {
            var root = tree(legacy);
            root.put("schemaVersion", "factory.candidate-source-manifest.v2");
            rejected(mapper.writeValueAsBytes(root));
        }
    }

    @Test
    void mixedScalarAndRecordFieldsAreRejectedInBothDirections() throws Exception {
        for (boolean legacy : List.of(false, true)) {
            for (String field : List.of("candidateId", "buildSessionId", "candidateHash")) {
                var root = tree(legacy);
                root.set(field, tree(!legacy).get(field));
                rejected(mapper.writeValueAsBytes(root));
            }
            for (String field : List.of("artifactRef", "contentHash")) {
                var root = tree(legacy);
                ((ObjectNode) root.get("files").get(0)).set(field, tree(!legacy).get("files").get(0).get(field));
                rejected(mapper.writeValueAsBytes(root));
            }
        }
    }

    @Test
    void historicalWrappersMustBeExactSingletonTextObjects() throws Exception {
        for (String field : List.of("candidateId", "buildSessionId", "candidateHash")) {
            var root = tree(true);
            ((ObjectNode) root.get(field)).put("unknown", true);
            rejected(mapper.writeValueAsBytes(root));
            root = tree(true);
            ((ObjectNode) root.get(field)).removeAll().put("value", 1);
            rejected(mapper.writeValueAsBytes(root));
        }
        for (String field : List.of("artifactRef", "contentHash")) {
            var root = tree(true);
            ((ObjectNode) root.get("files").get(0).get(field)).put("authority", "ignored?");
            rejected(mapper.writeValueAsBytes(root));
        }
    }

    @Test
    void unknownAndMissingManifestAndEntryFieldsRemainRejectedInBothForms() throws Exception {
        for (boolean legacy : List.of(false, true)) {
            var root = tree(legacy);
            root.put("unknown", true);
            rejected(mapper.writeValueAsBytes(root));
            root = tree(legacy);
            root.remove("candidateHash");
            rejected(mapper.writeValueAsBytes(root));
            root = tree(legacy);
            ((ObjectNode) root.get("files").get(0)).put("unknown", true);
            rejected(mapper.writeValueAsBytes(root));
            root = tree(legacy);
            ((ObjectNode) root.get("files").get(0)).remove("sizeBytes");
            rejected(mapper.writeValueAsBytes(root));
        }
    }

    @Test
    void duplicateKeysAndTrailingDocumentsNeverDisappearDuringLegacyNormalization() throws Exception {
        for (boolean legacy : List.of(false, true)) {
            String json = new String(wire(legacy), StandardCharsets.UTF_8);
            rejected((json + "{}").getBytes(StandardCharsets.UTF_8));
            rejected(("{\"schemaVersion\":\"" + CandidateSourceManifest.SCHEMA_VERSION + "\"," + json.substring(1))
                    .getBytes(StandardCharsets.UTF_8));
        }
        String legacy = new String(wire(true), StandardCharsets.UTF_8);
        rejected(legacy.replace("\"value\":\"wire-candidate\"",
                "\"value\":\"wire-candidate\",\"value\":\"wire-candidate\"").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void stringAndFractionalNumberCoercionsStayRejectedInBothForms() throws Exception {
        for (boolean legacy : List.of(false, true)) {
            for (String field : List.of("fileCount", "totalBytes")) {
                var root = tree(legacy);
                root.put(field, "1");
                rejected(mapper.writeValueAsBytes(root));
                root = tree(legacy);
                root.put(field, 1.0);
                rejected(mapper.writeValueAsBytes(root));
            }
            var root = tree(legacy);
            ((ObjectNode) root.get("files").get(0)).put("sizeBytes", "1");
            rejected(mapper.writeValueAsBytes(root));
        }
    }

    @Test
    void pathSafetyAndAggregateSizeChecksAreNotWeakenedByCompatibility() throws Exception {
        for (boolean legacy : List.of(false, true)) {
            var root = tree(legacy);
            ((ObjectNode) root.get("files").get(0)).put("path", "../outside.java");
            rejected(mapper.writeValueAsBytes(root));
            root = tree(legacy);
            root.put("totalBytes", 2);
            rejected(mapper.writeValueAsBytes(root));
            root = tree(legacy);
            root.put("fileCount", 0);
            rejected(mapper.writeValueAsBytes(root));
        }
    }

    @Test
    void nullEmptyOversizedAndNonObjectInputsFailClosed() {
        rejected(null);
        rejected(new byte[0]);
        rejected(new byte[JacksonWorkerProtocolArtifactDecoder.MAX_CANDIDATE_MANIFEST_BYTES + 1]);
        rejected("[]".getBytes(StandardCharsets.UTF_8));
        rejected("null".getBytes(StandardCharsets.UTF_8));
    }

    private void rejected(byte[] bytes) {
        Exception failure = assertThrows(Exception.class, () -> reader.read(bytes));
        assertTrue(failure instanceof IOException || failure instanceof IllegalArgumentException,
                "unexpected failure type: " + failure.getClass().getSimpleName());
    }

    private ObjectNode tree(boolean legacy) throws IOException { return (ObjectNode) mapper.readTree(wire(legacy)); }
    private byte[] wire(boolean legacy) throws IOException {
        return legacy ? mapper.writeValueAsBytes(manifest) : CandidateSourceManifestTestWire.scalarBytes(mapper, manifest);
    }
    private static CandidateSourceManifest manifest() {
        var entry = new CandidateSourceEntry("src/main/java/Agent.java", new ArtifactReference("artifact:wire-source"),
                new ContentHash("a".repeat(64)), 1);
        return new CandidateSourceManifest(CandidateSourceManifest.SCHEMA_VERSION, new CandidateId("wire-candidate"),
                new BuildSessionId("wire-session"), CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                new OrdinalSourceTreeHasher().hashEntries(List.of(entry)), 1, 1, List.of(entry));
    }
}
