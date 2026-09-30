package io.github.flowerjvm.factory.infrastructure.worker.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerRepairLock;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import io.github.flowerjvm.factory.infrastructure.production.JacksonAgentPackProductionCodec;
import io.github.flowerjvm.factory.infrastructure.verification.SecretScanner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic artifact/ledger fixtures only: no SDK, child process, authentication or model call. */
class CodexRepairInputMaterializerTest {
    private static final TenantId TENANT = new TenantId("repair-tenant");
    private static final BuildSessionId SESSION = new BuildSessionId("repair-session");
    private static final CandidateId BASE_ID = new CandidateId("base-candidate");
    private static final String OPERATION = "repair-operation";
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path temporary;

    @Test
    void repairStagesEveryCanonicalBaseFileAndIgnoresPoisonedConfiguredWorkspace() throws Exception {
        Fixture fixture = fixture();
        Files.createDirectories(fixture.workspace.resolve("source"));
        Files.writeString(fixture.workspace.resolve("source/base.txt"), "untrusted configured content");
        Files.writeString(fixture.workspace.resolve("source/extra.txt"), "not a base artifact");

        var result = materializer(fixture).materialize(fixture.order, OPERATION, fixture.state, fixture.workspace);

        assertEquals("unchanged base\n", Files.readString(result.workspaceRoot().resolve("source/base.txt")));
        assertEquals("previous content\n", Files.readString(result.workspaceRoot().resolve("source/changed.txt")));
        assertFalse(Files.exists(result.workspaceRoot().resolve("source/extra.txt")));
        assertEquals(List.of("source/changed.txt"), fixture.order.allowedWritePaths());
        assertEquals(fixture.order.allowedWritePaths(), result.manifest().repairLock().orElseThrow().allowedChangedPaths());
        String baseline = Files.readString(result.operationDirectory().resolve("workspace-baseline.json"));
        assertTrue(baseline.contains("source/base.txt"));
        assertTrue(baseline.contains("source/changed.txt"));
    }

    @Test
    void repairWithoutCanonicalRepositoryFailsClosedInsteadOfUsingConfiguredSource() throws Exception {
        Fixture fixture = fixture();
        var error = assertThrows(CodexWorkerProtocolException.class, () ->
                new CodexWorkerInputMaterializer(fixture.artifacts, mapper)
                        .materialize(fixture.order, OPERATION, fixture.state, fixture.workspace));
        assertEquals("CODING_WORKER_REPAIR_BASE_REPOSITORY_UNAVAILABLE", error.stableCode());
        assertFalse(Files.exists(fixture.state.resolve("tenants")));
    }

    @Test
    void canonicalRepositoryMissingOrWrongTenantSessionCandidateAndHashAreRejectedBeforeStaging() throws Exception {
        for (int mutation = 0; mutation < 5; mutation++) {
            Fixture fixture = fixture();
            CandidateVersion base = fixture.repository.value;
            fixture.repository.value = mutation == 0 ? null : new CandidateVersion(
                    mutation == 1 ? new CandidateId("other-base") : base.candidateId(),
                    mutation == 2 ? new TenantId("other-tenant") : base.tenantId(),
                    mutation == 3 ? new BuildSessionId("other-session") : base.buildSessionId(),
                    base.parentCandidateId(), base.sourceManifestRef(), mutation == 4 ? new ContentHash("0".repeat(64)) : base.sourceHash(),
                    base.dependencyLockRef(), base.dependencyLockHash(), base.toolchainLockRef(), base.toolchainLockHash(),
                    base.status(), base.createdByWorkOrderId(), base.createdAt());
            rejectBeforeStaging(fixture);
        }
    }

    @Test
    void canonicalManifestIdentityContentHashAndTreeHashAreAllRechecked() throws Exception {
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
                value -> value.put("candidateId", "other-base"),
                value -> value.put("buildSessionId", "other-session"),
                value -> value.put("candidateHash", "0".repeat(64)),
                value -> ((ObjectNode) value.withArray("files").get(0)).put("contentHash", "0".repeat(64)),
                value -> value.put("sourceLockAlgorithmId", "other-algorithm"))) {
            Fixture fixture = fixture();
            rewriteManifest(fixture, change);
            rejectBeforeStaging(fixture);
        }
        Fixture tampered = fixture();
        Artifact original = tampered.artifacts.values.get(tampered.manifestRef.value());
        tampered.artifacts.values.put(tampered.manifestRef.value(), new Artifact(TENANT, tampered.manifestRef,
                original.contentHash(), "application/json", bytes("{\"tampered\":true}")));
        rejectBeforeStaging(tampered);
    }

    @Test
    void everyBaseFileMustResolveWithExactTenantReferenceHashAndSize() throws Exception {
        for (int mutation = 0; mutation < 5; mutation++) {
            Fixture fixture = fixture();
            Artifact file = fixture.artifacts.values.get("artifact:base-file-v1");
            switch (mutation) {
                case 0 -> fixture.artifacts.values.remove(file.reference().value());
                case 1 -> fixture.artifacts.values.put(file.reference().value(), new Artifact(
                        new TenantId("wrong-tenant"), file.reference(), file.contentHash(), file.mediaType(), file.content()));
                case 2 -> fixture.artifacts.values.put(file.reference().value(), new Artifact(
                        TENANT, new ArtifactReference("artifact:wrong-ref-v1"), file.contentHash(), file.mediaType(), file.content()));
                case 3 -> fixture.artifacts.values.put(file.reference().value(), new Artifact(
                        TENANT, file.reference(), file.contentHash(), file.mediaType(), bytes("changed bytes")));
                case 4 -> rewriteManifest(fixture, value -> {
                    ObjectNode entry = (ObjectNode) value.withArray("files").get(0);
                    entry.put("sizeBytes", entry.get("sizeBytes").intValue() + 1);
                    value.put("totalBytes", value.get("totalBytes").intValue() + 1);
                });
                default -> throw new AssertionError();
            }
            rejectBeforeStaging(fixture);
        }
    }

    @Test
    void incompleteReadScopesRejectInsteadOfDroppingUnchangedBaseFiles() throws Exception {
        Fixture fixture = fixture();
        WorkOrder original = fixture.order;
        WorkOrder narrowed = new WorkOrder(original.workOrderId(), original.tenantId(), original.buildSessionId(),
                original.phase(), original.purpose(), original.revision(), original.supersedesWorkOrderId(),
                original.candidateId(), original.baseRevision(), original.instructionArtifactRef(), original.instructionHash(),
                original.inputArtifactManifestRef(), original.inputManifestHash(), original.workspaceRef(),
                List.of("source/changed.txt"), original.allowedWritePaths(), original.requiredCapabilities(),
                original.expectedOutputSchemaId(), original.expectedOutputSchemaVersion(), original.policySnapshotRef(),
                original.deadlineAt(), original.maxAttempts(), original.logicalIdempotencyKey(), original.createdByType(),
                original.createdByRef(), original.createdAt());
        assertThrows(CodexWorkerProtocolException.class, () -> materializer(fixture)
                .materialize(narrowed, OPERATION, fixture.state, fixture.workspace));
        assertFalse(Files.exists(fixture.state.resolve("tenants")));
    }

    @Test
    void publishedRepairSnapshotIsNotRecopiedOverWorkerChangesOnStatusOrRestart() throws Exception {
        Fixture fixture = fixture();
        var first = materializer(fixture).materialize(fixture.order, OPERATION, fixture.state, fixture.workspace);
        Files.writeString(first.workspaceRoot().resolve("source/changed.txt"), "changed by bounded worker\n");
        var resumed = materializer(fixture).materialize(fixture.order, OPERATION, fixture.state, fixture.workspace);
        assertEquals(first.workspaceRoot(), resumed.workspaceRoot());
        assertEquals("changed by bounded worker\n", Files.readString(resumed.workspaceRoot().resolve("source/changed.txt")));
        assertEquals("unchanged base\n", Files.readString(resumed.workspaceRoot().resolve("source/base.txt")));
    }

    @Test
    void repairRecoveryAfterAtomicPublishRebuildsOnlyOwnedMetadataAndPreservesCanonicalBaseline() throws Exception {
        Fixture fixture = fixture();
        var crashing = new CodexWorkerInputMaterializer(fixture.artifacts, mapper, new SecretScanner(),
                () -> { throw new SimulatedCrash(); }, fixture.repository);
        assertThrows(SimulatedCrash.class, () -> crashing.materialize(fixture.order, OPERATION, fixture.state, fixture.workspace));
        Path operation = operation(fixture);
        assertTrue(Files.isDirectory(operation.resolve("workspace")));
        assertFalse(Files.exists(operation.resolve("workspace.ready")));
        var recovered = materializer(fixture).materialize(fixture.order, OPERATION, fixture.state, fixture.workspace);
        assertTrue(Files.isRegularFile(operation.resolve("workspace.ready")));
        assertEquals("unchanged base\n", Files.readString(recovered.workspaceRoot().resolve("source/base.txt")));
    }

    @Test
    void publishedMetadataMustStillMatchTheCanonicalBaseRatherThanAnArbitraryHistoricalSnapshot() throws Exception {
        Fixture fixture = fixture();
        var first = materializer(fixture).materialize(fixture.order, OPERATION, fixture.state, fixture.workspace);
        byte[] forged = bytes("{\"schemaVersion\":\"factory.codex-workspace-baseline.v1\",\"files\":[]}");
        Files.write(first.workspaceRoot().resolve(".factory-snapshot/workspace-baseline.json"), forged);
        Files.write(first.operationDirectory().resolve("workspace-baseline.json"), forged);
        var error = assertThrows(CodexWorkerProtocolException.class, () -> materializer(fixture)
                .materialize(fixture.order, OPERATION, fixture.state, fixture.workspace));
        assertEquals("CODING_WORKER_REPAIR_BASE_SNAPSHOT_MISMATCH", error.stableCode());
    }

    private Fixture fixture() throws Exception {
        Path root = Files.createTempDirectory(temporary, "fixture-");
        Path workspace = Files.createDirectory(root.resolve("source-workspace"));
        Path state = Files.createDirectory(root.resolve("state"));
        MemoryArtifacts artifacts = new MemoryArtifacts();
        Artifact baseFile = artifacts.add("artifact:base-file-v1", bytes("unchanged base\n"));
        Artifact changedFile = artifacts.add("artifact:changed-file-v1", bytes("previous content\n"));
        ContentHash sourceHash = hash(bytes("source/base.txt\t" + baseFile.contentHash().sha256()
                + "\nsource/changed.txt\t" + changedFile.contentHash().sha256()));
        ObjectNode source = mapper.createObjectNode();
        source.put("schemaVersion", "factory.candidate-source-manifest.v1");
        source.put("candidateId", BASE_ID.value()); source.put("buildSessionId", SESSION.value());
        source.put("sourceLockAlgorithmId", "factory.ordinal-sha256.v1"); source.put("candidateHash", sourceHash.sha256());
        source.put("fileCount", 2); source.put("totalBytes", baseFile.content().length + changedFile.content().length);
        var sourceFiles = source.putArray("files");
        for (var entry : Map.of("source/base.txt", baseFile, "source/changed.txt", changedFile).entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            ObjectNode file = sourceFiles.addObject();
            file.put("path", entry.getKey()); file.put("artifactRef", entry.getValue().reference().value());
            file.put("contentHash", entry.getValue().contentHash().sha256()); file.put("sizeBytes", entry.getValue().content().length);
        }
        Artifact manifest = artifacts.add("artifact:base-source-manifest-v1", mapper.writeValueAsBytes(source));
        Artifact instruction = artifacts.add("artifact:instruction-v1", bytes("bounded repair\n"));
        Artifact policy = artifacts.add("artifact:policy-v1", bytes("no network\n"));
        Artifact authority = artifacts.add("artifact:authority-v1", bytes("immutable fixture authority\n"));
        Artifact finding = artifacts.add("artifact:finding-v1", bytes("repair the changed file\n"));
        var input = new CodingWorkerInputManifest(CodingWorkerInputManifest.SCHEMA_VERSION, new WorkOrderId("repair-work"), SESSION,
                "fixture-skill", "1.0.0", authority.reference(), authority.contentHash(), authority.reference(), authority.contentHash(),
                authority.reference(), authority.contentHash(), authority.reference(), authority.contentHash(), authority.reference(), authority.contentHash(),
                "fixture-gate", authority.reference(), authority.contentHash(), "factory.ordinal-sha256.v1",
                Optional.of(new CodingWorkerRepairLock(BASE_ID, sourceHash, finding.reference(), finding.contentHash(),
                        List.of("source/changed.txt"), 1, 2)));
        Artifact inputArtifact = artifacts.add("artifact:input-v1", new JacksonAgentPackProductionCodec(mapper).writeWorkerInput(input));
        Instant created = Instant.parse("2026-09-06T00:00:00Z");
        WorkOrder order = new WorkOrder(input.workOrderId(), TENANT, SESSION, "generate-candidate", "bounded repair", 2,
                Optional.of(new WorkOrderId("base-work")), Optional.of(BASE_ID), Optional.empty(), instruction.reference(), instruction.contentHash(),
                inputArtifact.reference(), inputArtifact.contentHash(), "workspace:fixture", List.of("source"), List.of("source/changed.txt"),
                Set.of(), "factory.pack-candidate", "1", policy.reference(), created.plusSeconds(60), 1, "repair-logical",
                WorkOrderCreatorType.SYSTEM, "fixture", created);
        CandidateVersion base = new CandidateVersion(BASE_ID, TENANT, SESSION, Optional.empty(), manifest.reference(), sourceHash,
                authority.reference(), authority.contentHash(), authority.reference(), authority.contentHash(), CandidateVersionStatus.GENERATED,
                new WorkOrderId("base-work"), created);
        return new Fixture(order, artifacts, new MutableBaseRepository(base), manifest.reference(), workspace, state);
    }

    private CodexWorkerInputMaterializer materializer(Fixture fixture) {
        return new CodexWorkerInputMaterializer(fixture.artifacts, mapper, fixture.repository);
    }
    private void rejectBeforeStaging(Fixture fixture) {
        assertEquals("CODING_WORKER_REPAIR_BASE_INVALID", assertThrows(CodexWorkerProtocolException.class, () ->
                materializer(fixture).materialize(fixture.order, OPERATION, fixture.state, fixture.workspace)).stableCode());
        assertFalse(Files.exists(fixture.state.resolve("tenants")));
    }
    private void rewriteManifest(Fixture fixture, Consumer<ObjectNode> change) throws Exception {
        ObjectNode source = (ObjectNode) mapper.readTree(fixture.artifacts.values.get(fixture.manifestRef.value()).content());
        change.accept(source);
        fixture.artifacts.add(fixture.manifestRef.value(), mapper.writeValueAsBytes(source));
    }
    private Path operation(Fixture fixture) {
        return fixture.state.resolve("tenants").resolve(CodexWorkerInputMaterializer.tenantPartition(TENANT))
                .resolve("operations").resolve(hash(bytes(OPERATION)).sha256());
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static ContentHash hash(byte[] bytes) {
        try { return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private record Fixture(WorkOrder order, MemoryArtifacts artifacts, MutableBaseRepository repository,
            ArtifactReference manifestRef, Path workspace, Path state) {}
    private static final class MemoryArtifacts implements ArtifactStore {
        private final Map<String, Artifact> values = new HashMap<>();
        Artifact add(String reference, byte[] bytes) {
            Artifact artifact = new Artifact(TENANT, new ArtifactReference(reference), hash(bytes), "text/plain", bytes);
            store(artifact); return artifact;
        }
        @Override public ArtifactReference store(Artifact artifact) {
            values.put(artifact.reference().value(), artifact); return artifact.reference();
        }
        @Override public Optional<Artifact> find(TenantId tenant, ArtifactReference reference) { return Optional.ofNullable(values.get(reference.value())); }
    }
    private static final class MutableBaseRepository implements CandidateVersionRepository {
        private CandidateVersion value;
        private MutableBaseRepository(CandidateVersion value) { this.value = value; }
        @Override public void create(CandidateVersion candidate) { throw new AssertionError("fixture is read only"); }
        @Override public Optional<CandidateVersion> find(TenantId tenantId, CandidateId candidateId) { return Optional.ofNullable(value); }
        @Override public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                TenantId tenantId, BuildSessionId buildSessionId, WorkOrderId workOrderId) { return Optional.empty(); }
    }
    private static final class SimulatedCrash extends RuntimeException {}
}
