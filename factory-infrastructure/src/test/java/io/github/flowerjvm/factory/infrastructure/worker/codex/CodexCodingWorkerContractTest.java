package io.github.flowerjvm.factory.infrastructure.worker.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.application.work.WorkerAttemptProofs;
import io.github.flowerjvm.factory.application.candidate.CandidateIngestionService;
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
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import io.github.flowerjvm.factory.contracts.worker.WorkerAttemptToken;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerCancelRequest;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilityCatalog;
import io.github.flowerjvm.factory.contracts.worker.WorkerDispatchRequest;
import io.github.flowerjvm.factory.contracts.worker.WorkerLookupState;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.factory.contracts.worker.WorkerStatusRequest;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import io.github.flowerjvm.factory.infrastructure.verification.SecretScanner;
import io.github.flowerjvm.factory.infrastructure.verification.CandidateSourceManifestReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexCodingWorkerContractTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final WorkOrderId ORDER_ID = new WorkOrderId("work-1");
    private static final WorkerRunId RUN_ID = new WorkerRunId("run-1");
    private static final String OPERATION_ID = "operation-1";
    private static final WorkerAttemptToken TOKEN = new WorkerAttemptToken("attempt-token");
    private static final CodexCredentialIsolationVerifier TEST_ONLY_PROVEN_ISOLATION = binding ->
            new CodexCredentialIsolationVerifier.ProvenIsolation(
                    binding.bindingId(),
                    "test-only-synthetic-isolation",
                    "0".repeat(64),
                    Instant.parse("2026-08-20T00:00:00Z"));

    @TempDir Path temporary;

    @Test
    void fakeProtocolCoversCapabilitiesSubmitStatusPromotionAndTokenRedaction() throws Exception {
        Fixture fixture = fixture("terminal-success", Duration.ofSeconds(10));
        Files.createDirectories(fixture.workspace.resolve("candidate"));
        Files.writeString(fixture.workspace.resolve("candidate/result.txt"), "safe candidate\n");

        assertEquals(9, fixture.worker.capabilities().values().size());
        var submission = fixture.worker.submit(new WorkerDispatchRequest(
                fixture.order, RUN_ID, OPERATION_ID, TOKEN));
        assertEquals(WorkerRunStatus.WAITING_EXTERNAL, submission.status());
        assertTrue(submission.externalSessionRef().orElseThrow().startsWith("codex-operation:"));

        var observation = fixture.worker.status(new WorkerStatusRequest(
                TENANT, ORDER_ID, RUN_ID, OPERATION_ID, TOKEN));
        assertEquals(WorkerLookupState.FOUND, observation.lookupState());
        assertEquals(WorkerRunStatus.SUCCEEDED, observation.snapshot().orElseThrow().status());
        assertTrue(observation.snapshot().orElseThrow().effectAcceptedAt().isPresent());
        assertTrue(observation.snapshot().orElseThrow().effectTerminalAt().isPresent());
        assertFalse(observation.snapshot().orElseThrow().effectTerminalAt().orElseThrow()
                .isBefore(observation.snapshot().orElseThrow().effectAcceptedAt().orElseThrow()));
        var payload = observation.terminalPayload().orElseThrow();
        assertTrue(WorkerAttemptProofs.matches(
                TOKEN.reveal(), payload.eventId(), payload.operationId(), payload.workerRunId(), payload.attemptProof()));
        assertTrue(payload.result().isPresent());
        assertTrue(observation.snapshot().orElseThrow().resultArtifact().isPresent());

        String operationHash = sha256(OPERATION_ID.getBytes(StandardCharsets.UTF_8));
        Path operation = fixture.stateRoot.resolve("tenants")
                .resolve(CodexWorkerInputMaterializer.tenantPartition(TENANT))
                .resolve("operations")
                .resolve(operationHash);
        String request = Files.readString(operation.resolve("last-request.json"));
        String state = Files.readString(operation.resolve("fake-state.json"));
        assertFalse(request.contains(TOKEN.reveal()));
        assertFalse(state.contains(TOKEN.reveal()));
        assertFalse(request.contains("FACTORY_WORKER_BINDING_SECRET"));
    }

    @Test
    void exactTupleCancelIsIdempotentAndNeverSendsFreeFormReason() throws Exception {
        Fixture fixture = fixture("normal", Duration.ofSeconds(10));
        fixture.worker.submit(new WorkerDispatchRequest(fixture.order, RUN_ID, OPERATION_ID, TOKEN));
        var result = fixture.worker.cancel(new WorkerCancelRequest(
                TENANT, ORDER_ID, RUN_ID, OPERATION_ID, TOKEN, "FACTORY_CANCELLED"));
        assertEquals(WorkerRunStatus.CANCEL_REQUESTED, result.status());
        assertEquals("WORKER_CANCEL_REQUESTED", result.stableCode());
    }

    @Test
    void strictResponseUnknownFieldBecomesUnknownNotFoundNeverAliasesUnknown() throws Exception {
        Fixture fixture = fixture("unknown-field", Duration.ofSeconds(10));
        fixture.worker.submit(new WorkerDispatchRequest(fixture.order, RUN_ID, OPERATION_ID, TOKEN));
        var observation = fixture.worker.status(new WorkerStatusRequest(
                TENANT, ORDER_ID, RUN_ID, OPERATION_ID, TOKEN));
        assertEquals(WorkerLookupState.UNKNOWN, observation.lookupState());
        assertEquals("CODING_WORKER_PROTOCOL_INVALID", observation.stableCode().orElseThrow());

        Fixture notFound = fixture("normal", Duration.ofSeconds(10));
        var absent = notFound.worker.status(new WorkerStatusRequest(
                TENANT, ORDER_ID, RUN_ID, "operation-missing", TOKEN));
        assertEquals(WorkerLookupState.NOT_FOUND, absent.lookupState());
    }

    @Test
    void concatenatedJsonResponseIsRejectedAsUnknown() throws Exception {
        Fixture fixture = fixture("trailing-json", Duration.ofSeconds(10));
        fixture.worker.submit(new WorkerDispatchRequest(fixture.order, RUN_ID, OPERATION_ID, TOKEN));

        var observation = fixture.worker.status(new WorkerStatusRequest(
                TENANT, ORDER_ID, RUN_ID, OPERATION_ID, TOKEN));

        assertEquals(WorkerLookupState.UNKNOWN, observation.lookupState());
        assertEquals("CODING_WORKER_PROTOCOL_INVALID", observation.stableCode().orElseThrow());
    }

    @Test
    void repairPromotionKeepsReadOnlyBaseAndChangedFileInSourceManifest() throws Exception {
        Fixture fixture = fixture("terminal-success-repair", Duration.ofSeconds(10), true);
        Files.createDirectories(fixture.workspace.resolve("source"));
        Files.writeString(fixture.workspace.resolve("source/base.txt"), "unchanged base\n");
        Files.writeString(fixture.workspace.resolve("source/changed.txt"), "repaired content\n");
        fixture.worker.submit(new WorkerDispatchRequest(fixture.order, RUN_ID, OPERATION_ID, TOKEN));

        var payload = fixture.worker.status(new WorkerStatusRequest(
                TENANT, ORDER_ID, RUN_ID, OPERATION_ID, TOKEN)).terminalPayload().orElseThrow();
        var sourceReference = payload.result().orElseThrow().candidateOutput().orElseThrow().sourceManifestRef();
        String manifest = new String(
                fixture.artifacts.find(TENANT, sourceReference).orElseThrow().content(), StandardCharsets.UTF_8);

        assertTrue(manifest.contains("\"path\":\"source/base.txt\""));
        assertTrue(manifest.contains("\"path\":\"source/changed.txt\""));
        byte[] promotedBytes = fixture.artifacts.find(TENANT, sourceReference).orElseThrow().content();
        var mapper = new ObjectMapper();
        assertTrue(mapper.readTree(promotedBytes).get("candidateHash").isTextual());
        assertEquals(new JacksonWorkerProtocolArtifactDecoder(mapper).decodeCandidateSourceManifest(promotedBytes),
                new CandidateSourceManifestReader(mapper).read(promotedBytes),
                "actual promoter output must have identical meaning for ingest, verifier and repair readers");
    }

    @Test
    void promoterRejectsUndeclaredReadOnlyMutationBeforeArtifactPromotion() throws Exception {
        Fixture fixture = fixture(
                "terminal-success-repair-readonly-mutated", Duration.ofSeconds(10), true);
        Files.createDirectories(fixture.workspace.resolve("source"));
        Files.writeString(fixture.workspace.resolve("source/base.txt"), "unchanged base\n");
        Files.writeString(fixture.workspace.resolve("source/changed.txt"), "repaired content\n");
        fixture.worker.submit(new WorkerDispatchRequest(fixture.order, RUN_ID, OPERATION_ID, TOKEN));

        var observation = fixture.worker.status(new WorkerStatusRequest(
                TENANT, ORDER_ID, RUN_ID, OPERATION_ID, TOKEN));

        assertEquals(WorkerLookupState.UNKNOWN, observation.lookupState());
        assertEquals("CODING_WORKER_READ_ONLY_MUTATION", observation.stableCode().orElseThrow());
    }

    @Test
    void designPromotionProducesPrimaryOnlyResultAcceptedByCandidateIngestion() throws Exception {
        Fixture fixture = fixture("terminal-success-design", Duration.ofSeconds(10), false, true);
        fixture.worker.submit(new WorkerDispatchRequest(fixture.order, RUN_ID, OPERATION_ID, TOKEN));

        var payload = fixture.worker.status(new WorkerStatusRequest(
                TENANT, ORDER_ID, RUN_ID, OPERATION_ID, TOKEN)).terminalPayload().orElseThrow();
        var result = payload.result().orElseThrow();
        assertTrue(result.candidateOutput().isEmpty());
        String blueprint = new String(fixture.artifacts.find(
                TENANT, result.primaryArtifactRef()).orElseThrow().content(), StandardCharsets.UTF_8);
        assertTrue(blueprint.contains("agent-blueprint"));

        var protocolArtifacts = new WorkerProtocolArtifacts(
                fixture.artifacts, new JacksonWorkerProtocolArtifactDecoder(new ObjectMapper()));
        var ingestion = new CandidateIngestionService(protocolArtifacts, emptyCandidates());
        var prepared = ingestion.validateAndPromote(
                fixture.order, requestedRun(fixture.order), result, Instant.parse("2026-08-20T00:01:00Z"));

        assertTrue(prepared.candidate().isEmpty());
    }

    @Test
    void preseededOperationsLinkCannotRedirectMaterializedInputs() throws Exception {
        Fixture fixture = fixture("normal", Duration.ofSeconds(10));
        Path outside = temporary.resolve("outside-state");
        Files.createDirectories(outside);
        Path tenantState = fixture.stateRoot.resolve("tenants")
                .resolve(CodexWorkerInputMaterializer.tenantPartition(TENANT));
        Files.createDirectories(tenantState);
        try {
            Files.createSymbolicLink(tenantState.resolve("operations"), outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }

        var failure = assertThrows(
                io.github.flowerjvm.factory.contracts.worker.WorkerDispatchException.class,
                () -> fixture.worker.submit(new WorkerDispatchRequest(
                        fixture.order, RUN_ID, OPERATION_ID, TOKEN)));

        assertEquals("CODING_WORKER_STAGED_INPUT_LINK_REJECTED", failure.stableCode());
        assertFalse(Files.exists(outside.resolve(sha256(OPERATION_ID.getBytes(StandardCharsets.UTF_8)))));
    }

    @Test
    void restartRecoversCrashAfterAtomicWorkspacePublishWithoutRecopyingOrSecondEffect() throws Exception {
        Fixture fixture = fixture("normal", Duration.ofSeconds(10));
        Files.createDirectories(fixture.workspace.resolve("inputs"));
        Files.writeString(fixture.workspace.resolve("inputs/base.txt"), "snapshot before crash\n");
        var crashing = new CodexWorkerInputMaterializer(
                fixture.artifacts,
                new ObjectMapper(),
                new SecretScanner(),
                () -> { throw new SimulatedMaterializerCrash(); });

        assertThrows(SimulatedMaterializerCrash.class, () -> crashing.materialize(
                fixture.order, OPERATION_ID, fixture.stateRoot, fixture.workspace));

        Path operation = fixture.stateRoot.resolve("tenants")
                .resolve(CodexWorkerInputMaterializer.tenantPartition(TENANT))
                .resolve("operations")
                .resolve(sha256(OPERATION_ID.getBytes(StandardCharsets.UTF_8)));
        assertTrue(Files.isDirectory(operation.resolve("workspace")));
        assertFalse(Files.exists(operation.resolve("workspace.ready")));
        assertFalse(Files.exists(operation.resolve("fake-state.json")));

        Files.writeString(fixture.workspace.resolve("inputs/base.txt"), "source changed after crash\n");
        var recovered = new CodexWorkerInputMaterializer(fixture.artifacts, new ObjectMapper())
                .materialize(fixture.order, OPERATION_ID, fixture.stateRoot, fixture.workspace);

        assertEquals(
                "snapshot before crash\n",
                Files.readString(recovered.workspaceRoot().resolve("inputs/base.txt")));
        assertTrue(Files.isRegularFile(operation.resolve("workspace.ready")));
        assertTrue(Files.isRegularFile(operation.resolve("workspace-baseline.json")));
        assertFalse(Files.exists(operation.resolve("fake-state.json")));

        var submission = fixture.worker.submit(new WorkerDispatchRequest(
                fixture.order, RUN_ID, OPERATION_ID, TOKEN));
        assertEquals(WorkerRunStatus.WAITING_EXTERNAL, submission.status());
        assertTrue(Files.isRegularFile(operation.resolve("fake-state.json")));
    }

    @Test
    void boundedProcessTimeoutDoesNotExposeTokenOrProviderOutput() throws Exception {
        Fixture fixture = fixture("timeout", Duration.ofMillis(150));
        var failure = assertThrows(
                io.github.flowerjvm.factory.contracts.worker.WorkerDispatchException.class,
                () -> fixture.worker.submit(new WorkerDispatchRequest(fixture.order, RUN_ID, OPERATION_ID, TOKEN)));
        assertEquals("CODING_WORKER_PROCESS_TIMEOUT", failure.stableCode());
        assertFalse(failure.toString().contains(TOKEN.reveal()));
        assertEquals(io.github.flowerjvm.factory.contracts.worker.WorkerEffectCertainty.UNCERTAIN,
                failure.effectCertainty());
    }

    @Test
    void attemptProofGoldenVectorMatchesNodeContract() {
        assertEquals(
                "8e095c1c0627141eae7f9cab4feb7bfd5a4e7036b1f24bcc1eed984d10646fb6",
                WorkerAttemptProofs.create("attempt-token", "event-1", "operation-1", RUN_ID));
    }

    @Test
    void bindingToStringRedactsDedicatedProfileAndEnvironment() throws Exception {
        Fixture fixture = fixture("normal", Duration.ofSeconds(10));
        String binding = fixture.binding.toString();
        assertFalse(binding.contains(fixture.codexHome.toString()));
        assertTrue(binding.contains("processEnvironment=[REDACTED]"));
    }

    @Test
    void bindingRejectsNodeLoaderProxyAndCaseFoldedEnvironmentAmbiguity() throws Exception {
        Fixture fixture = fixture("normal", Duration.ofSeconds(10));
        assertThrows(IllegalArgumentException.class, () -> bindingWithEnvironment(
                fixture, Map.of("NODE_OPTIONS", "--require=malicious.mjs")));
        assertThrows(IllegalArgumentException.class, () -> bindingWithEnvironment(
                fixture, Map.of("HTTPS_PROXY", "http://proxy.invalid")));
        assertThrows(IllegalArgumentException.class, () -> bindingWithEnvironment(
                fixture, Map.of("PATH", "one", "Path", "two")));
    }

    @Test
    void productionDefaultFailsClosedWhenCredentialReadIsolationIsUnproven() throws Exception {
        Fixture fixture = fixture("normal", Duration.ofSeconds(10));
        CodexCodingWorker defaultDenied = new CodexCodingWorker(
                fixture.binding, fixture.artifacts, new InMemoryWorkOrders(fixture.order));

        var failure = assertThrows(
                io.github.flowerjvm.factory.contracts.worker.WorkerDispatchException.class,
                defaultDenied::capabilities);

        assertEquals("WORKER_CREDENTIAL_ISOLATION_UNPROVEN", failure.stableCode());
    }

    private Fixture fixture(String mode, Duration timeout) throws Exception {
        return fixture(mode, timeout, false, false);
    }

    private Fixture fixture(String mode, Duration timeout, boolean repair) throws Exception {
        return fixture(mode, timeout, repair, false);
    }

    private Fixture fixture(String mode, Duration timeout, boolean repair, boolean design) throws Exception {
        Path workspaceBase = temporary.resolve("workspaces-" + mode + "-" + System.nanoTime());
        Path workspace = workspaceBase.resolve("one");
        Path stateRoot = temporary.resolve("state-" + mode + "-" + System.nanoTime());
        Path codexHome = temporary.resolve("codex-home-" + mode + "-" + System.nanoTime());
        Files.createDirectories(workspace);
        Files.createDirectories(stateRoot);
        Files.createDirectories(codexHome);
        Path runner = Path.of(Objects.requireNonNull(
                getClass().getResource("/codex-worker/fake-runner.mjs")).toURI());
        CodexWorkerBinding binding = new CodexWorkerBinding(
                "codex-test",
                findNode(),
                runner,
                stateRoot,
                workspaceBase,
                codexHome,
                Map.of("workspace:one", workspace),
                minimalEnvironment(),
                Optional.of(mode),
                Optional.empty(),
                timeout);
        InMemoryArtifacts artifacts = new InMemoryArtifacts();
        WorkOrder order = order(artifacts, repair, design);
        InMemoryWorkOrders orders = new InMemoryWorkOrders(order);
        CodexCodingWorker worker = new CodexCodingWorker(
                binding, artifacts, orders, TEST_ONLY_PROVEN_ISOLATION,
                repair ? canonicalRepairCandidates(artifacts, order) : emptyCandidates());
        return new Fixture(worker, binding, order, workspace, stateRoot, codexHome, artifacts);
    }

    private CodexWorkerBinding bindingWithEnvironment(Fixture fixture, Map<String, String> environment) {
        return new CodexWorkerBinding(
                "codex-test", findNode(), fixture.binding.runnerEntrypoint(), fixture.stateRoot,
                fixture.workspace.getParent(), fixture.codexHome,
                Map.of("workspace:one", fixture.workspace), environment,
                Optional.of("normal"), Optional.empty(), Duration.ofSeconds(1));
    }

    private WorkOrder order(InMemoryArtifacts artifacts, boolean repair, boolean design) {
        var instruction = artifacts.add(TENANT, "artifact:instruction", "bounded instruction\n", "text/plain");
        var policy = artifacts.add(TENANT, "artifact:policy", "no network\n", "text/plain");
        var skill = artifacts.add(TENANT, "artifact:skill", "skill\n", "text/plain");
        var dependency = artifacts.add(TENANT, "artifact:dependency", "dependency\n", "application/json");
        var toolchain = artifacts.add(TENANT, "artifact:toolchain", "toolchain\n", "application/json");
        var api = artifacts.add(TENANT, "artifact:api", "api\n", "application/json");
        var product = artifacts.add(TENANT, "artifact:product", "product\n", "application/json");
        var tests = artifacts.add(TENANT, "artifact:tests", "tests\n", "application/json");
        var finding = repair
                ? Optional.of(artifacts.add(TENANT, "artifact:finding", "finding\n", "application/json"))
                : Optional.<Artifact>empty();
        String repairJson = repair
                ? ("{\"baseCandidateId\":\"base-candidate\",\"baseCandidateHash\":\"%s\","
                        + "\"findingManifestRef\":\"%s\",\"findingManifestHash\":\"%s\","
                        + "\"allowedChangedPaths\":[\"source/changed.txt\"],\"repairRound\":1,\"maxRepairRounds\":3}")
                        .formatted(
                                canonicalRepairHash(),
                                finding.orElseThrow().reference().value(),
                                finding.orElseThrow().contentHash().sha256())
                : "null";
        String manifestJson = """
                {"schemaVersion":"factory.coding-worker-input-manifest.v1","workOrderId":"work-1",
                "buildSessionId":"session-1","skillId":"factory-skill","skillVersion":"1",
                "skillArtifactRef":"%s","skillHash":"%s","dependencyLockRef":"%s","dependencyLockHash":"%s",
                "toolchainLockRef":"%s","toolchainLockHash":"%s","apiSignatureIndexRef":"%s","apiSignatureIndexHash":"%s",
                "productContractBundleRef":"%s","productContractBundleHash":"%s","gateProfile":"strict",
                "requirementTestMatrixRef":"%s","requirementTestMatrixHash":"%s",
                "sourceLockAlgorithmId":"factory.ordinal-sha256.v1","repairLock":%s}
                """.formatted(
                skill.reference().value(), skill.contentHash().sha256(),
                dependency.reference().value(), dependency.contentHash().sha256(),
                toolchain.reference().value(), toolchain.contentHash().sha256(),
                api.reference().value(), api.contentHash().sha256(),
                product.reference().value(), product.contentHash().sha256(),
                tests.reference().value(), tests.contentHash().sha256(), repairJson);
        var manifest = artifacts.add(TENANT, "artifact:input-manifest", manifestJson, "application/json");
        return new WorkOrder(
                ORDER_ID,
                TENANT,
                new BuildSessionId("session-1"),
                design ? "design-agent" : "generate-candidate",
                "Generate a bounded candidate",
                1,
                Optional.empty(),
                repair ? Optional.of(new CandidateId("base-candidate")) : Optional.empty(),
                Optional.empty(),
                instruction.reference(),
                instruction.contentHash(),
                manifest.reference(),
                manifest.contentHash(),
                "workspace:one",
                repair ? List.of("source") : List.of("inputs"),
                repair ? List.of("source/changed.txt") : design ? List.of("blueprint.json") : List.of("candidate"),
                Set.of(
                        WorkerCapabilityCatalog.REPOSITORY_READ,
                        WorkerCapabilityCatalog.BOUNDED_PATCH_WRITE),
                design ? "agent-blueprint" : "factory.pack-candidate",
                "1",
                policy.reference(),
                Instant.parse("2099-01-01T00:00:00Z"),
                1,
                "logical-1",
                WorkOrderCreatorType.SYSTEM,
                "factory-test",
                Instant.parse("2026-08-20T00:00:00Z"));
    }

    private static Path findNode() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "node.exe" : "node";
        for (String value : Optional.ofNullable(System.getenv("PATH")).orElse("").split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(value).resolve(executable);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        throw new IllegalStateException("Node 20+ is required for Codex protocol contract tests");
    }

    private static Map<String, String> minimalEnvironment() {
        var result = new HashMap<String, String>();
        var folded = new java.util.HashSet<String>();
        for (String name : List.of("PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "PATHEXT", "COMSPEC")) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank() && folded.add(name.toUpperCase(java.util.Locale.ROOT))) {
                result.put(name, value);
            }
        }
        return result;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static WorkerRunRecord requestedRun(WorkOrder order) {
        Instant created = Instant.parse("2026-08-20T00:00:00Z");
        return new WorkerRunRecord(
                RUN_ID,
                TENANT,
                order.buildSessionId(),
                ORDER_ID,
                1,
                "codex-test",
                "1",
                new WorkerCapabilities(order.requiredCapabilities()),
                WorkerRunStatus.REQUESTED,
                Optional.empty(),
                OPERATION_ID,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                order.deadlineAt(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                created,
                created);
    }

    private static CandidateVersionRepository emptyCandidates() {
        return new CandidateVersionRepository() {
            @Override
            public void create(CandidateVersion candidateVersion) {
                throw new AssertionError("design ingestion must not create a CandidateVersion");
            }

            @Override
            public Optional<CandidateVersion> find(TenantId tenantId, CandidateId candidateId) {
                return Optional.empty();
            }

            @Override
            public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                    TenantId tenantId, BuildSessionId buildSessionId, WorkOrderId workOrderId) {
                return Optional.empty();
            }
        };
    }

    private static String canonicalRepairHash() {
        return sha256(("source/base.txt\t" + sha256("unchanged base\n".getBytes(StandardCharsets.UTF_8))
                + "\nsource/changed.txt\t" + sha256("previous content\n".getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.UTF_8));
    }

    private static CandidateVersionRepository canonicalRepairCandidates(InMemoryArtifacts artifacts, WorkOrder order) {
        var baseFile = artifacts.add(TENANT, "artifact:canonical-base-file", "unchanged base\n", "text/plain");
        var changedFile = artifacts.add(TENANT, "artifact:canonical-changed-file", "previous content\n", "text/plain");
        String source = """
                {"schemaVersion":"factory.candidate-source-manifest.v1","candidateId":"base-candidate",
                "buildSessionId":"session-1","sourceLockAlgorithmId":"factory.ordinal-sha256.v1",
                "candidateHash":"%s","fileCount":2,"totalBytes":%d,"files":[
                {"path":"source/base.txt","artifactRef":"%s","contentHash":"%s","sizeBytes":%d},
                {"path":"source/changed.txt","artifactRef":"%s","contentHash":"%s","sizeBytes":%d}]}
                """.formatted(canonicalRepairHash(), baseFile.content().length + changedFile.content().length,
                baseFile.reference().value(), baseFile.contentHash().sha256(), baseFile.content().length,
                changedFile.reference().value(), changedFile.contentHash().sha256(), changedFile.content().length);
        Artifact manifest = artifacts.add(TENANT, "artifact:canonical-base-manifest", source, "application/json");
        var input = new JacksonWorkerProtocolArtifactDecoder(new ObjectMapper()).decodeInputManifest(
                artifacts.find(TENANT, order.inputArtifactManifestRef()).orElseThrow().content());
        CandidateVersion base = new CandidateVersion(new CandidateId("base-candidate"), TENANT, order.buildSessionId(),
                Optional.empty(), manifest.reference(), new ContentHash(canonicalRepairHash()),
                input.dependencyLockRef(), input.dependencyLockHash(), input.toolchainLockRef(), input.toolchainLockHash(),
                CandidateVersionStatus.GENERATED, new WorkOrderId("base-generation-order"), order.createdAt());
        return new CandidateVersionRepository() {
            @Override public void create(CandidateVersion candidate) { throw new AssertionError("read only base fixture"); }
            @Override public Optional<CandidateVersion> find(TenantId tenantId, CandidateId candidateId) {
                return base.tenantId().equals(tenantId) && base.candidateId().equals(candidateId)
                        ? Optional.of(base) : Optional.empty();
            }
            @Override public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                    TenantId tenantId, BuildSessionId buildSessionId, WorkOrderId workOrderId) { return Optional.empty(); }
        };
    }

    private record Fixture(
            CodexCodingWorker worker,
            CodexWorkerBinding binding,
            WorkOrder order,
            Path workspace,
            Path stateRoot,
            Path codexHome,
            InMemoryArtifacts artifacts) {}

    private static final class InMemoryArtifacts implements ArtifactStore {
        private final Map<String, Artifact> values = new ConcurrentHashMap<>();

        Artifact add(TenantId tenant, String referenceValue, String text, String mediaType) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            ContentHash hash = new ContentHash(sha256(bytes));
            Artifact artifact = new Artifact(
                    tenant, new ArtifactReference(referenceValue), hash, mediaType, bytes);
            store(artifact);
            return artifact;
        }

        @Override
        public ArtifactReference store(Artifact artifact) {
            String key = key(artifact.tenantId(), artifact.reference());
            Artifact existing = values.putIfAbsent(key, artifact);
            if (existing != null && !existing.contentHash().equals(artifact.contentHash())) {
                throw new IllegalArgumentException("immutable artifact conflict");
            }
            return artifact.reference();
        }

        @Override
        public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            return Optional.ofNullable(values.get(key(tenantId, reference)));
        }

        private static String key(TenantId tenantId, ArtifactReference reference) {
            return tenantId.value() + "\n" + reference.value();
        }
    }

    private static final class InMemoryWorkOrders implements WorkOrderRepository {
        private final WorkOrder order;

        private InMemoryWorkOrders(WorkOrder order) {
            this.order = order;
        }

        @Override
        public void create(WorkOrder workOrder) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<WorkOrder> find(TenantId tenantId, WorkOrderId workOrderId) {
            return order.tenantId().equals(tenantId) && order.workOrderId().equals(workOrderId)
                    ? Optional.of(order) : Optional.empty();
        }
    }

    private static final class SimulatedMaterializerCrash extends RuntimeException {}
}
