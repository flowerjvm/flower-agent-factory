package io.github.flowerjvm.factory.infrastructure.verification;

import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.ARTIFACT_CHECKSUM_MISMATCH;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.WORKSPACE_PATH_COLLISION;
import static io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes.WORKSPACE_QUOTA_EXCEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceEntry;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CandidateWorkspaceMaterializerTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private final OrdinalSourceTreeHasher hasher = new OrdinalSourceTreeHasher();

    @TempDir
    Path temporaryDirectory;

    @Test
    void materializesOnlyVerifiedRegularArtifactsIntoNewRoot() throws Exception {
        var fixture = fixture(List.of(file("src/main/App.java", "class App {}")), defaultLimits());
        Path workspace = temporaryDirectory.resolve("new-workspace");

        var result = fixture.materializer().materialize(TENANT, fixture.manifest(), workspace);

        assertEquals("class App {}", Files.readString(workspace.resolve("src/main/App.java")));
        assertEquals(fixture.manifest().candidateHash(), result.sourceHash());
        assertFalse(Files.isSymbolicLink(workspace.resolve("src/main/App.java")));
    }

    @Test
    void candidateContractRejectsTraversalAbsoluteDriveUncBackslashAndEmptySegments() {
        for (String invalid : List.of(
                "../x", "/x", "C:/x", "//server/share", "\\\\server\\share", "a\\b",
                "a//b", "./x", "a/./b", "CON", "aux.txt", "name.", "name ", "a/<bad>")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture(List.of(file(invalid, "x")), defaultLimits()));
        }
    }

    @Test
    void candidateContractRejectsCaseCollisionsBeforeWriting() {
        Path workspace = temporaryDirectory.resolve("collision");

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture(
                        List.of(file("src/App.java", "one"), file("SRC/app.java", "two")),
                        defaultLimits()));
        assertFalse(Files.exists(workspace));
    }

    @Test
    void rejectsArtifactChecksumOrSizeMismatchBeforeWriting() {
        var fixture = fixture(List.of(file("pom.xml", "valid")), defaultLimits());
        CandidateSourceEntry declared = fixture.manifest().files().getFirst();
        var tampered = new Artifact(
                TENANT,
                declared.artifactRef(),
                declared.contentHash(),
                "text/plain",
                "tampered".getBytes(StandardCharsets.UTF_8));
        fixture.store().values.put(declared.artifactRef(), tampered);
        Path workspace = temporaryDirectory.resolve("checksum");

        assertCode(ARTIFACT_CHECKSUM_MISMATCH, () ->
                fixture.materializer().materialize(TENANT, fixture.manifest(), workspace));
        assertFalse(Files.exists(workspace));
    }

    @Test
    void rejectsPerFileAggregateFileCountAndPathQuotas() {
        var limits = new CandidateWorkspaceMaterializer.Limits(1, 4, 4, 8);
        for (List<FileData> files : List.of(
                List.of(file("one", "12345")),
                List.of(file("one", "12"), file("two", "34")),
                List.of(file("long-path", "1")))) {
            var fixture = fixture(files, limits);
            assertCode(WORKSPACE_QUOTA_EXCEEDED, () -> fixture.materializer()
                    .materialize(TENANT, fixture.manifest(), temporaryDirectory.resolve("quota-" + files.hashCode())));
        }
    }

    @Test
    void requiresWorkspaceNotToExist() throws Exception {
        var fixture = fixture(List.of(file("pom.xml", "x")), defaultLimits());
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("existing"));

        assertCode(WORKSPACE_PATH_COLLISION, () ->
                fixture.materializer().materialize(TENANT, fixture.manifest(), workspace));
        assertTrue(Files.exists(workspace));
    }

    private Fixture fixture(List<FileData> files, CandidateWorkspaceMaterializer.Limits limits) {
        var store = new MemoryArtifactStore();
        var entries = files.stream().map(file -> {
            byte[] bytes = file.content().getBytes(StandardCharsets.UTF_8);
            ContentHash hash = hasher.sha256(bytes);
            ArtifactReference reference = new ArtifactReference("source/" + Math.abs(file.path().hashCode()));
            store.store(new Artifact(TENANT, reference, hash, "text/plain", bytes));
            return new CandidateSourceEntry(file.path(), reference, hash, bytes.length);
        }).toList();
        long total = entries.stream().mapToLong(CandidateSourceEntry::sizeBytes).sum();
        var manifest = new CandidateSourceManifest(
                CandidateSourceManifest.SCHEMA_VERSION,
                new CandidateId("candidate-a"),
                new BuildSessionId("session-a"),
                CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                hasher.hashEntries(entries),
                entries.size(),
                total,
                entries);
        return new Fixture(store, new CandidateWorkspaceMaterializer(store, hasher, limits), manifest);
    }

    private static CandidateWorkspaceMaterializer.Limits defaultLimits() {
        return CandidateWorkspaceMaterializer.DEFAULT_LIMITS;
    }

    private static FileData file(String path, String content) {
        return new FileData(path, content);
    }

    private static void assertCode(String expected, ThrowingOperation operation) {
        var failure = assertThrows(CandidateMaterializationException.class, operation::run);
        assertEquals(expected, failure.stableCode());
    }

    private record FileData(String path, String content) {}

    private record Fixture(
            MemoryArtifactStore store,
            CandidateWorkspaceMaterializer materializer,
            CandidateSourceManifest manifest) {}

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private static final class MemoryArtifactStore implements ArtifactStore {
        private final Map<ArtifactReference, Artifact> values = new HashMap<>();

        @Override
        public ArtifactReference store(Artifact artifact) {
            values.put(artifact.reference(), artifact);
            return artifact.reference();
        }

        @Override
        public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            return Optional.ofNullable(values.get(reference)).filter(value -> value.tenantId().equals(tenantId));
        }
    }
}
