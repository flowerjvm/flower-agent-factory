package io.github.flowerjvm.factory.application.candidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.build.BuildPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.flow.LedgerBackedCreateCustomerAgentFlowCoordinator;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifactDecoder;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceEntry;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.worker.CandidateOutputLock;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerRepairLock;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerResultManifest;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerProtocol;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CandidateIngestionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final TenantId TENANT = new TenantId("tenant-ingest");
    private static final BuildSessionId SESSION = new BuildSessionId("session-ingest");
    private static final WorkOrderId ORDER_ID = new WorkOrderId("order-ingest");
    private static final WorkerRunId RUN_ID = new WorkerRunId("run-ingest");

    @Test
    void validatesExactArtifactsAndPersistsOnlyAfterCanonicalWorkerSuccess() {
        Fixture fixture = new Fixture(false, false);

        assertEquals(
                LedgerBackedCreateCustomerAgentFlowCoordinator.GENERATE_WORK_ORDER_PHASE,
                fixture.order.phase());

        CandidateIngestionService.PreparedResult prepared = fixture.service.validateAndPromote(
                fixture.order, fixture.workerRun, fixture.result, NOW.plusSeconds(5));
        assertEquals(Optional.empty(), fixture.candidates.find(
                TENANT, prepared.candidate().orElseThrow().candidateId()));

        WorkerRunRecord canonical = fixture.workerRun.complete(
                WorkerRunStatus.SUCCEEDED,
                Optional.of(fixture.result.primaryArtifactRef()),
                Optional.of(fixture.result.primaryResultHash()),
                "WORKER_COMPLETED",
                "trusted completion",
                WorkerRetryDisposition.NEVER,
                NOW.plusSeconds(5));
        CandidateVersion candidate = fixture.service
                .persistAfterCanonicalSuccess(prepared, canonical)
                .orElseThrow();

        assertEquals(fixture.source.candidateHash(), candidate.sourceHash());
        assertEquals(Optional.of(candidate), fixture.candidates.find(TENANT, candidate.candidateId()));
    }

    @Test
    void actualCoordinatorDesignPhaseAcceptsAResultWithoutCandidateOutput() {
        Fixture fixture = new Fixture(false, false);
        WorkOrder designOrder = workOrder(
                fixture.order.inputArtifactManifestRef(),
                fixture.order.inputManifestHash(),
                LedgerBackedCreateCustomerAgentFlowCoordinator.DESIGN_WORK_ORDER_PHASE);
        CodingWorkerResultManifest designResult = new CodingWorkerResultManifest(
                WorkerProtocol.RESULT_SCHEMA_VERSION,
                ORDER_ID,
                RUN_ID,
                "operation-ingest",
                designOrder.expectedOutputSchemaId(),
                designOrder.expectedOutputSchemaVersion(),
                designOrder.inputArtifactManifestRef(),
                designOrder.inputManifestHash(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NOW.plusSeconds(3));

        CandidateIngestionService.PreparedResult prepared = fixture.service.validateAndPromote(
                designOrder, fixture.workerRun, designResult, NOW.plusSeconds(5));

        assertEquals(BuildSessionPhase.DESIGN_AGENT.id(), designOrder.phase());
        assertEquals(Optional.empty(), prepared.candidate());
    }

    @Test
    void legacyEnumPhaseNameDoesNotAliasTheCanonicalGeneratePhase() {
        Fixture fixture = new Fixture(false, false);
        WorkOrder legacyOrder = workOrder(
                fixture.order.inputArtifactManifestRef(),
                fixture.order.inputManifestHash(),
                BuildPhase.GENERATE_CANDIDATE.name());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.validateAndPromote(
                        legacyOrder, fixture.workerRun, fixture.result, NOW.plusSeconds(5)));

        assertEquals("WORKER_RESULT_CANDIDATE_SHAPE_MISMATCH", failure.getMessage());
    }

    @Test
    void rejectsArtifactWhoseStoredHashMetadataDoesNotMatchItsBytes() {
        Fixture fixture = new Fixture(true, false);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.validateAndPromote(
                        fixture.order, fixture.workerRun, fixture.result, NOW.plusSeconds(5)));

        assertEquals("WORKER_ARTIFACT_HASH_MISMATCH", failure.getMessage());
        assertEquals(0, fixture.candidates.created);
    }

    @Test
    void rejectsCandidateAggregateHashNotDerivedFromCanonicalPathAndFileHashes() {
        Fixture fixture = new Fixture(false, true);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.validateAndPromote(
                        fixture.order, fixture.workerRun, fixture.result, NOW.plusSeconds(5)));

        assertEquals("WORKER_CANDIDATE_SOURCE_HASH_MISMATCH", failure.getMessage());
        assertEquals(0, fixture.candidates.created);
    }

    @Test
    void rejectsCandidateManifestPathOutsideTrustedWorkOrderScope() {
        Fixture fixture = new Fixture(false, false, true);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.validateAndPromote(
                        fixture.order, fixture.workerRun, fixture.result, NOW.plusSeconds(5)));

        assertEquals("WORKER_CANDIDATE_PATH_OUTSIDE_SCOPE", failure.getMessage());
        assertEquals(0, fixture.candidates.created);
    }

    @Test
    void repairOutputCannotChangeAPathOutsideTheTrustedRepairGrant() {
        RepairFixture fixture = new RepairFixture();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.validateAndPromote(
                        fixture.order, fixture.workerRun, fixture.result, NOW.plusSeconds(5)));

        assertEquals("WORKER_REPAIR_CHANGED_PATH_OUTSIDE_GRANT", failure.getMessage());
    }

    private static final class Fixture {
        private final MemoryCandidates candidates = new MemoryCandidates();
        private final WorkOrder order;
        private final WorkerRunRecord workerRun;
        private final CandidateSourceManifest source;
        private final CodingWorkerResultManifest result;
        private final CandidateIngestionService service;

        private Fixture(boolean tamperFileBytes, boolean mismatchedAggregateHash) {
            this(tamperFileBytes, mismatchedAggregateHash, false);
        }

        private Fixture(
                boolean tamperFileBytes,
                boolean mismatchedAggregateHash,
                boolean outsideWorkOrderScope) {
            Map<String, Artifact> stored = new LinkedHashMap<>();
            ArtifactReference skillRef = artifact(stored, "artifact:skill", "skill");
            ArtifactReference dependencyRef = artifact(stored, "artifact:dependencies", "dependencies");
            ArtifactReference toolchainRef = artifact(stored, "artifact:toolchain", "toolchain");
            ArtifactReference apiRef = artifact(stored, "artifact:api", "api");
            ArtifactReference productRef = artifact(stored, "artifact:product", "product");
            ArtifactReference matrixRef = artifact(stored, "artifact:matrix", "matrix");

            byte[] inputBytes = bytes("input-manifest");
            ArtifactReference inputRef = new ArtifactReference("artifact:input-manifest");
            ContentHash inputHash = hash(inputBytes);
            CodingWorkerInputManifest input = new CodingWorkerInputManifest(
                    CodingWorkerInputManifest.SCHEMA_VERSION,
                    ORDER_ID,
                    SESSION,
                    "factory-java",
                    "1",
                    skillRef,
                    stored.get(skillRef.value()).contentHash(),
                    dependencyRef,
                    stored.get(dependencyRef.value()).contentHash(),
                    toolchainRef,
                    stored.get(toolchainRef.value()).contentHash(),
                    apiRef,
                    stored.get(apiRef.value()).contentHash(),
                    productRef,
                    stored.get(productRef.value()).contentHash(),
                    "strict",
                    matrixRef,
                    stored.get(matrixRef.value()).contentHash(),
                    CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                    Optional.empty());
            stored.put(inputRef.value(), new Artifact(
                    TENANT, inputRef, inputHash, "application/json", inputBytes));

            byte[] expectedFileBytes = bytes("class Main {}\n");
            ContentHash fileHash = hash(expectedFileBytes);
            ArtifactReference fileRef = new ArtifactReference("artifact:source-main");
            byte[] storedFileBytes = tamperFileBytes ? bytes("tampered\n") : expectedFileBytes;
            stored.put(fileRef.value(), new Artifact(
                    TENANT, fileRef, fileHash, "application/octet-stream", storedFileBytes));
            CandidateSourceEntry entry = new CandidateSourceEntry(
                    outsideWorkOrderScope ? "pom.xml" : "src/Main.java",
                    fileRef,
                    fileHash,
                    expectedFileBytes.length);
            ContentHash candidateHash = mismatchedAggregateHash
                    ? new ContentHash("f".repeat(64))
                    : treeHash(List.of(entry));
            CandidateId candidateId = new CandidateId("candidate-ingest");
            source = new CandidateSourceManifest(
                    CandidateSourceManifest.SCHEMA_VERSION,
                    candidateId,
                    SESSION,
                    CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                    candidateHash,
                    1,
                    expectedFileBytes.length,
                    List.of(entry));
            byte[] sourceBytes = bytes("source-manifest");
            ArtifactReference sourceRef = new ArtifactReference("artifact:source-manifest");
            ContentHash sourceManifestHash = hash(sourceBytes);
            stored.put(sourceRef.value(), new Artifact(
                    TENANT, sourceRef, sourceManifestHash, "application/json", sourceBytes));

            CandidateOutputLock output = new CandidateOutputLock(
                    candidateId,
                    Optional.empty(),
                    sourceRef,
                    sourceManifestHash,
                    candidateHash,
                    dependencyRef,
                    stored.get(dependencyRef.value()).contentHash(),
                    toolchainRef,
                    stored.get(toolchainRef.value()).contentHash());
            result = new CodingWorkerResultManifest(
                    WorkerProtocol.RESULT_SCHEMA_VERSION,
                    ORDER_ID,
                    RUN_ID,
                    "operation-ingest",
                    "factory.candidate.v1",
                    "1",
                    sourceRef,
                    candidateHash,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(output),
                    NOW.plusSeconds(3));
            order = workOrder(inputRef, inputHash);
            workerRun = workerRun();
            WorkerProtocolArtifacts artifacts = new WorkerProtocolArtifacts(
                    artifactStore(stored), decoder(input, Map.of("source-manifest", source)));
            service = new CandidateIngestionService(artifacts, candidates);
        }
    }

    private static final class RepairFixture {
        private final WorkOrder order;
        private final WorkerRunRecord workerRun;
        private final CodingWorkerResultManifest result;
        private final CandidateIngestionService service;

        private RepairFixture() {
            Map<String, Artifact> stored = new LinkedHashMap<>();
            ArtifactReference skillRef = artifact(stored, "artifact:repair-skill", "repair-skill");
            ArtifactReference dependencyRef = artifact(
                    stored, "artifact:repair-dependencies", "repair-dependencies");
            ArtifactReference toolchainRef = artifact(
                    stored, "artifact:repair-toolchain", "repair-toolchain");
            ArtifactReference apiRef = artifact(stored, "artifact:repair-api", "repair-api");
            ArtifactReference productRef = artifact(
                    stored, "artifact:repair-product", "repair-product");
            ArtifactReference matrixRef = artifact(
                    stored, "artifact:repair-matrix", "repair-matrix");
            ArtifactReference findingRef = artifact(
                    stored, "artifact:repair-findings", "repair-findings");

            CandidateId baseId = new CandidateId("candidate-base");
            CandidateSourceEntry baseEntry = new CandidateSourceEntry(
                    "src/Main.java",
                    new ArtifactReference("artifact:base-main"),
                    hash(bytes("class Main {}\n")),
                    bytes("class Main {}\n").length);
            ContentHash baseHash = treeHash(List.of(baseEntry));
            CandidateSourceManifest baseSource = new CandidateSourceManifest(
                    CandidateSourceManifest.SCHEMA_VERSION,
                    baseId,
                    SESSION,
                    CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                    baseHash,
                    1,
                    baseEntry.sizeBytes(),
                    List.of(baseEntry));
            byte[] baseSourceBytes = bytes("base-source-manifest");
            ArtifactReference baseSourceRef = new ArtifactReference("artifact:base-source-manifest");
            stored.put(baseSourceRef.value(), new Artifact(
                    TENANT, baseSourceRef, hash(baseSourceBytes), "application/json", baseSourceBytes));

            MemoryCandidates candidates = new MemoryCandidates();
            candidates.create(new CandidateVersion(
                    baseId,
                    TENANT,
                    SESSION,
                    Optional.empty(),
                    baseSourceRef,
                    baseHash,
                    dependencyRef,
                    stored.get(dependencyRef.value()).contentHash(),
                    toolchainRef,
                    stored.get(toolchainRef.value()).contentHash(),
                    CandidateVersionStatus.GENERATED,
                    new WorkOrderId("order-base"),
                    NOW));

            CodingWorkerRepairLock repairLock = new CodingWorkerRepairLock(
                    baseId,
                    baseHash,
                    findingRef,
                    stored.get(findingRef.value()).contentHash(),
                    List.of("src/Main.java"),
                    1,
                    3);
            byte[] inputBytes = bytes("repair-input-manifest");
            ArtifactReference inputRef = new ArtifactReference("artifact:repair-input-manifest");
            ContentHash inputHash = hash(inputBytes);
            CodingWorkerInputManifest input = new CodingWorkerInputManifest(
                    CodingWorkerInputManifest.SCHEMA_VERSION,
                    ORDER_ID,
                    SESSION,
                    "factory-java",
                    "1",
                    skillRef,
                    stored.get(skillRef.value()).contentHash(),
                    dependencyRef,
                    stored.get(dependencyRef.value()).contentHash(),
                    toolchainRef,
                    stored.get(toolchainRef.value()).contentHash(),
                    apiRef,
                    stored.get(apiRef.value()).contentHash(),
                    productRef,
                    stored.get(productRef.value()).contentHash(),
                    "strict",
                    matrixRef,
                    stored.get(matrixRef.value()).contentHash(),
                    CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                    Optional.of(repairLock));
            stored.put(inputRef.value(), new Artifact(
                    TENANT, inputRef, inputHash, "application/json", inputBytes));

            byte[] mainBytes = bytes("class Main { int fixed; }\n");
            byte[] readmeBytes = bytes("ungranted file\n");
            ArtifactReference mainRef = new ArtifactReference("artifact:repair-main");
            ArtifactReference readmeRef = new ArtifactReference("artifact:repair-readme");
            CandidateSourceEntry main = new CandidateSourceEntry(
                    "src/Main.java", mainRef, hash(mainBytes), mainBytes.length);
            CandidateSourceEntry readme = new CandidateSourceEntry(
                    "README.md", readmeRef, hash(readmeBytes), readmeBytes.length);
            stored.put(mainRef.value(), new Artifact(
                    TENANT, mainRef, main.contentHash(), "application/octet-stream", mainBytes));
            stored.put(readmeRef.value(), new Artifact(
                    TENANT, readmeRef, readme.contentHash(), "application/octet-stream", readmeBytes));
            List<CandidateSourceEntry> files = List.of(readme, main);
            ContentHash candidateHash = treeHash(files);
            CandidateId candidateId = new CandidateId("candidate-repair");
            CandidateSourceManifest repairSource = new CandidateSourceManifest(
                    CandidateSourceManifest.SCHEMA_VERSION,
                    candidateId,
                    SESSION,
                    CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                    candidateHash,
                    files.size(),
                    files.stream().mapToLong(CandidateSourceEntry::sizeBytes).sum(),
                    files);
            byte[] repairSourceBytes = bytes("repair-source-manifest");
            ArtifactReference repairSourceRef = new ArtifactReference("artifact:repair-source-manifest");
            ContentHash repairSourceHash = hash(repairSourceBytes);
            stored.put(repairSourceRef.value(), new Artifact(
                    TENANT, repairSourceRef, repairSourceHash, "application/json", repairSourceBytes));
            CandidateOutputLock output = new CandidateOutputLock(
                    candidateId,
                    Optional.of(baseId),
                    repairSourceRef,
                    repairSourceHash,
                    candidateHash,
                    dependencyRef,
                    stored.get(dependencyRef.value()).contentHash(),
                    toolchainRef,
                    stored.get(toolchainRef.value()).contentHash());
            result = new CodingWorkerResultManifest(
                    WorkerProtocol.RESULT_SCHEMA_VERSION,
                    ORDER_ID,
                    RUN_ID,
                    "operation-ingest",
                    "factory.candidate.v1",
                    "1",
                    repairSourceRef,
                    candidateHash,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(output),
                    NOW.plusSeconds(3));
            order = new WorkOrder(
                    ORDER_ID, TENANT, SESSION, BuildSessionPhase.GENERATE_CANDIDATE.id(),
                    "Repair a locked candidate", 2, Optional.empty(), Optional.of(baseId), Optional.empty(),
                    new ArtifactReference("artifact:instruction"), hash(bytes("instruction")),
                    inputRef, inputHash, "workspace:ingest",
                    List.of("src", "README.md"), List.of("src"), Set.of(),
                    "factory.candidate.v1", "1", new ArtifactReference("artifact:policy"),
                    NOW.plusSeconds(300), 1, "logical-ingest-repair", WorkOrderCreatorType.SYSTEM,
                    "test", NOW);
            workerRun = workerRun();
            WorkerProtocolArtifacts artifacts = new WorkerProtocolArtifacts(
                    artifactStore(stored),
                    decoder(input, Map.of(
                            "base-source-manifest", baseSource,
                            "repair-source-manifest", repairSource)));
            service = new CandidateIngestionService(artifacts, candidates);
        }
    }

    private static WorkOrder workOrder(ArtifactReference inputRef, ContentHash inputHash) {
        return workOrder(inputRef, inputHash, BuildSessionPhase.GENERATE_CANDIDATE.id());
    }

    private static WorkOrder workOrder(
            ArtifactReference inputRef, ContentHash inputHash, String phase) {
        return new WorkOrder(
                ORDER_ID, TENANT, SESSION, phase,
                "Generate a locked candidate", 1, Optional.empty(), Optional.empty(), Optional.empty(),
                new ArtifactReference("artifact:instruction"), hash(bytes("instruction")),
                inputRef, inputHash, "workspace:ingest", List.of("src"), List.of("src"), Set.of(),
                "factory.candidate.v1", "1", new ArtifactReference("artifact:policy"),
                NOW.plusSeconds(300), 1, "logical-ingest", WorkOrderCreatorType.SYSTEM,
                "test", NOW);
    }

    private static WorkerRunRecord workerRun() {
        WorkerRunRecord requested = new WorkerRunRecord(
                RUN_ID, TENANT, SESSION, ORDER_ID, 1, "codex-worker", "1",
                new WorkerCapabilities(Set.of()), WorkerRunStatus.REQUESTED, Optional.empty(),
                "operation-ingest", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                NOW.plusSeconds(300), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW, NOW);
        return requested.startDispatch(
                        new DispatchOutboxId("dispatch-ingest"),
                        "action-ingest",
                        WorkerDispatchOperationIds.hashAttemptToken("attempt-ingest"),
                        NOW.plusSeconds(1))
                .awaitExternal("operation-ingest", NOW.plusSeconds(2));
    }

    private static ArtifactReference artifact(
            Map<String, Artifact> artifacts, String referenceValue, String content) {
        byte[] value = bytes(content);
        ArtifactReference reference = new ArtifactReference(referenceValue);
        ContentHash hash = hash(value);
        artifacts.put(referenceValue, new Artifact(
                TENANT, reference, hash, "application/octet-stream", value));
        return reference;
    }

    private static ArtifactStore artifactStore(Map<String, Artifact> artifacts) {
        return new ArtifactStore() {
            @Override public ArtifactReference store(Artifact artifact) {
                throw new UnsupportedOperationException();
            }
            @Override public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
                Artifact artifact = artifacts.get(reference.value());
                return artifact != null && artifact.tenantId().equals(tenantId)
                        ? Optional.of(artifact) : Optional.empty();
            }
        };
    }

    private static WorkerProtocolArtifactDecoder decoder(
            CodingWorkerInputManifest input,
            Map<String, CandidateSourceManifest> sourceByMarker) {
        return new WorkerProtocolArtifactDecoder() {
            @Override public CodingWorkerCompletionPayload decodeCompletionPayload(byte[] content) {
                throw new UnsupportedOperationException();
            }
            @Override public CodingWorkerInputManifest decodeInputManifest(byte[] content) {
                return input;
            }
            @Override public CandidateSourceManifest decodeCandidateSourceManifest(byte[] content) {
                CandidateSourceManifest source = sourceByMarker.get(new String(content, StandardCharsets.UTF_8));
                if (source == null) throw new IllegalArgumentException("unknown source fixture");
                return source;
            }
        };
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static ContentHash treeHash(List<CandidateSourceEntry> entries) {
        String material = entries.stream()
                .sorted(Comparator.comparing(CandidateSourceEntry::path))
                .map(entry -> entry.path() + "\t" + entry.contentHash().sha256())
                .collect(java.util.stream.Collectors.joining("\n"));
        return hash(bytes(material));
    }

    private static ContentHash hash(byte[] bytes) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class MemoryCandidates implements CandidateVersionRepository {
        private final Map<CandidateId, CandidateVersion> values = new LinkedHashMap<>();
        private int created;

        @Override public void create(CandidateVersion candidateVersion) {
            if (values.putIfAbsent(candidateVersion.candidateId(), candidateVersion) != null) {
                throw new IllegalStateException("duplicate candidate");
            }
            created++;
        }
        @Override public Optional<CandidateVersion> find(TenantId tenantId, CandidateId candidateId) {
            CandidateVersion value = values.get(candidateId);
            return value != null && value.tenantId().equals(tenantId)
                    ? Optional.of(value) : Optional.empty();
        }
        @Override public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                TenantId tenantId, BuildSessionId buildSessionId, WorkOrderId workOrderId) {
            return values.values().stream()
                    .filter(value -> value.tenantId().equals(tenantId))
                    .filter(value -> value.buildSessionId().equals(buildSessionId))
                    .filter(value -> value.createdByWorkOrderId().equals(workOrderId))
                    .findFirst();
        }
    }
}
