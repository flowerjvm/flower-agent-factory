package io.github.flowerjvm.factory.application.verification;

import static org.junit.jupiter.api.Assertions.*;

import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.action.VerificationRunAction;
import io.github.flowerjvm.factory.application.action.VerificationRunIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.VerificationRunInput;
import io.github.flowerjvm.factory.application.action.VerificationRunPolicyGate;
import io.github.flowerjvm.factory.application.action.VerificationRunPreExecutionGuard;
import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.candidate.*;
import io.github.flowerjvm.factory.application.certification.AgentPackProductContract;
import io.github.flowerjvm.factory.application.work.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.factory.contracts.verification.*;
import io.github.flowerjvm.factory.contracts.worker.*;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AgentPackGenerationVerificationProfilesTest {
    private static final TenantId TENANT = new TenantId("profile-tenant");
    private static final BuildSessionId SESSION = new BuildSessionId("profile-session");
    private static final WorkOrderId ORDER = new WorkOrderId("profile-order");
    private static final CandidateId CANDIDATE = new CandidateId("profile-candidate");
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    private static final String MAINTENANCE = MaintenanceInvestigationProductContract.GATE_PROFILE;
    private static final String PR4 = ActionBackedVerificationRunLauncher.GATE_PROFILE;

    @Test
    void exactCodeOwnedContractSelectsMaintenanceWithoutChangingLegacyPr4Identity() {
        var fixture = new Fixture();
        assertEquals(MAINTENANCE, fixture.profiles.profileFor(fixture.candidate));
        VerificationRun selected = fixture.launcher().ensureRequested(fixture.session, fixture.candidate, NOW);
        assertEquals(MAINTENANCE, selected.gateProfile());
        assertEquals(1, fixture.actionCalls);
        var legacyId = ActionBackedVerificationRunLauncher.deriveId(
                fixture.session, fixture.candidate, fixture.fixture.hash());
        assertEquals(legacyId, ActionBackedVerificationRunLauncher.deriveId(
                fixture.session, fixture.candidate, fixture.fixture.hash(), PR4));
        assertNotEquals(legacyId, selected.verificationRunId());
        // Independent pre-change v1 byte layout, including the unchanged PR4 slot.
        String material = String.join("\n", "factory.verification-run-id.v1", TENANT.value(), SESSION.value(),
                CANDIDATE.value(), fixture.candidate.sourceHash().sha256(), fixture.dependency.reference().value(),
                fixture.dependency.hash().sha256(), "factory-v0.1-pr4", fixture.toolchain.reference().value(),
                fixture.toolchain.hash().sha256(), fixture.fixture.hash().sha256());
        assertEquals("verify-" + sha256(material.getBytes(StandardCharsets.UTF_8)).sha256(), legacyId.value());
        fixture.input = copy(fixture.input, "productContractBundleRef", AgentPackProductContract.lock().reference(),
                "productContractBundleHash", AgentPackProductContract.lock().hash(), "gateProfile", PR4);
        fixture.artifacts.store(AgentPackProductContract.artifact(TENANT));
        assertEquals(PR4, fixture.profiles.profileFor(fixture.candidate));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidGenerationLocks")
    void invalidGenerationLocksAreRejectedAtEveryAdmissionBoundary(
            String label, Consumer<Fixture> mutation) {
        var fixture = new Fixture();
        mutation.accept(fixture);
        var failure = assertThrows(RuntimeException.class,
                () -> fixture.profiles.profileFor(fixture.candidate), label);
        assertFalse(failure.getMessage().isBlank());
        assertThrows(RuntimeException.class,
                () -> fixture.launcher().ensureRequested(fixture.session, fixture.candidate, NOW));
        assertEquals(0, fixture.actionCalls);
        assertFalse(fixture.policy().evaluate(fixture.proposal(), VerificationRunAction.definition(),
                fixture.context(true)).allowedToExecuteNow());
        assertFalse(fixture.guard().check(fixture.proposal(), VerificationRunAction.definition(),
                fixture.context(true), PolicyDecision.allow()).allowed());
        assertThrows(RuntimeException.class,
                () -> fixture.service(fixture.profiles).execute(TENANT, fixture.run.verificationRunId(), 0));
        assertEquals(VerificationRunStatus.REQUESTED, fixture.run.status());
        assertEquals(0, fixture.verifierCalls);
    }

    static Stream<Arguments> invalidGenerationLocks() {
        Map<String, Consumer<Fixture>> cases = new LinkedHashMap<>();
        cases.put("missing order", f -> f.order = null);
        cases.put("foreign tenant returned by repository", f -> f.order = copy(f.order, "tenantId", new TenantId("other")));
        cases.put("different order identity", f -> f.order = copy(f.order, "workOrderId", new WorkOrderId("other")));
        cases.put("different order session", f -> f.order = copy(f.order, "buildSessionId", new BuildSessionId("other")));
        cases.put("non generation phase", f -> f.order = copy(f.order, "phase", BuildSessionPhase.DESIGN_AGENT.id()));
        cases.put("different input order", f -> f.input = copy(f.input, "workOrderId", new WorkOrderId("other")));
        cases.put("different input session", f -> f.input = copy(f.input, "buildSessionId", new BuildSessionId("other")));
        cases.put("candidate dependency reference", f -> f.candidate = copy(f.candidate, "dependencyLockRef", ref("wrong")));
        cases.put("candidate dependency hash", f -> f.candidate = copy(f.candidate, "dependencyLockHash", hash("wrong")));
        cases.put("candidate toolchain reference", f -> f.candidate = copy(f.candidate, "toolchainLockRef", ref("wrong")));
        cases.put("candidate toolchain hash", f -> f.candidate = copy(f.candidate, "toolchainLockHash", hash("wrong")));
        cases.put("unknown contract", f -> f.input = copy(f.input, "productContractBundleRef", ref("unknown")));
        cases.put("contract hash mismatch", f -> f.input = copy(f.input, "productContractBundleHash", hash("wrong")));
        cases.put("worker selected gate string", f -> f.input = copy(f.input, "gateProfile", "worker-says-pass"));
        cases.put("legacy gate downgrade", f -> f.input = copy(f.input, "gateProfile", PR4));
        cases.put("different API reference", f -> f.input = copy(f.input, "apiSignatureIndexRef", ref("wrong")));
        cases.put("different API hash", f -> f.input = copy(f.input, "apiSignatureIndexHash", hash("wrong")));
        cases.put("different matrix reference", f -> f.input = copy(f.input, "requirementTestMatrixRef", ref("wrong")));
        cases.put("different matrix hash", f -> f.input = copy(f.input, "requirementTestMatrixHash", hash("wrong")));
        cases.put("source algorithm mismatch", f -> f.input = copy(f.input, "sourceLockAlgorithmId", "different"));
        cases.put("missing contract artifact", f -> f.artifacts.values.remove(MaintenanceInvestigationProductContract.lock().reference()));
        cases.put("input bytes hash mismatch", f -> f.artifacts.corrupt(f.order.inputArtifactManifestRef()));
        cases.put("API bytes hash mismatch", f -> f.artifacts.corrupt(f.input.apiSignatureIndexRef()));
        cases.put("strict decoder rejection", f -> f.rejectInput = true);
        cases.put("invented parent candidate", f -> f.candidate = copy(f.candidate, "parentCandidateId", Optional.of(new CandidateId("parent"))));
        cases.put("repair shape mismatch", f -> f.order = copy(f.order, "candidateId", Optional.of(new CandidateId("parent"))));
        cases.put("repair base hash mismatch", f -> { f.withRepair(); f.base = copy(f.base, "sourceHash", hash("wrong")); });
        cases.put("repair base session mismatch", f -> { f.withRepair(); f.base = copy(f.base, "buildSessionId", new BuildSessionId("other")); });
        cases.put("repair finding hash mismatch", f -> { f.withRepair(); f.artifacts.corrupt(f.input.repairLock().orElseThrow().findingManifestRef()); });
        return cases.entrySet().stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    @Test
    void authorizedMaintenanceKeepsPermissionCurrentVersionFixtureAndTerminalCasRules() {
        var fixture = new Fixture();
        assertTrue(fixture.policy().evaluate(fixture.proposal(), VerificationRunAction.definition(),
                fixture.context(true)).allowedToExecuteNow());
        assertFalse(fixture.policy().evaluate(fixture.proposal(), VerificationRunAction.definition(),
                fixture.context(false)).allowedToExecuteNow());
        assertTrue(fixture.guard().check(fixture.proposal(), VerificationRunAction.definition(),
                fixture.context(true), PolicyDecision.allow()).allowed());
        var requested = fixture.run;
        fixture.run = copy(fixture.run, "fixtureSetHash", hash("wrong"));
        assertFalse(fixture.guard().check(fixture.proposal(), VerificationRunAction.definition(),
                fixture.context(true), PolicyDecision.allow()).allowed());
        fixture.run = requested;
        var service = fixture.service(fixture.profiles);
        assertTrue(service.execute(TENANT, requested.verificationRunId(), 0).executedNow());
        assertFalse(service.execute(TENANT, requested.verificationRunId(), 0).executedNow());
        assertEquals(1, fixture.verifierCalls);
        assertEquals(VerificationRunStatus.PASSED, fixture.run.status());
        assertFalse(fixture.guard().check(fixture.proposal(), VerificationRunAction.definition(),
                fixture.context(true), PolicyDecision.allow()).allowed());
        // Authorized transport replay can still reach the duplicate owner after terminal CAS.
        assertTrue(fixture.policy().evaluate(fixture.proposal(), VerificationRunAction.definition(),
                fixture.context(true)).allowedToExecuteNow());
    }

    @Test
    void legacyConstructorsCannotAuthorizeMaintenanceAndRunProfileCannotOverrideGeneration() {
        var fixture = new Fixture();
        assertFalse(new VerificationRunPolicyGate(fixture.runs, fixture.candidates)
                .evaluate(fixture.proposal(), VerificationRunAction.definition(), fixture.context(true)).allowedToExecuteNow());
        assertFalse(new VerificationRunPreExecutionGuard(fixture.sessions, fixture.candidates, fixture.runs,
                fixture.fixture.hash(), Clock.fixed(NOW, ZoneOffset.UTC))
                .check(fixture.proposal(), VerificationRunAction.definition(), fixture.context(true), PolicyDecision.allow()).allowed());
        assertThrows(IllegalArgumentException.class, () -> fixture.service(AgentPackGenerationVerificationProfiles.legacyPr4Only())
                .execute(TENANT, fixture.run.verificationRunId(), 0));
        fixture.run = copy(fixture.run, "gateProfile", PR4);
        assertFalse(fixture.policy().evaluate(fixture.proposal(), VerificationRunAction.definition(),
                fixture.context(true)).allowedToExecuteNow());
        assertThrows(IllegalArgumentException.class,
                () -> fixture.service(fixture.profiles).execute(TENANT, fixture.run.verificationRunId(), 0));
        assertEquals(0, fixture.verifierCalls);
    }

    @Test
    void exactRepairLineageStillSelectsTheSameCodeOwnedProductGate() {
        var fixture = new Fixture();
        fixture.withRepair();
        assertEquals(MAINTENANCE, fixture.profiles.profileFor(fixture.candidate));
    }

    private static final class Fixture {
        final MemoryArtifacts artifacts = new MemoryArtifacts();
        final CertificationArtifactLock dependency = artifacts.add("dependency");
        final CertificationArtifactLock toolchain = artifacts.add("toolchain");
        final CertificationArtifactLock skill = artifacts.add("skill");
        final CertificationArtifactLock fixture = artifacts.add("fixture");
        final CertificationArtifactLock source = artifacts.add("source");
        final CertificationArtifactLock result = artifacts.add("result");
        CandidateVersion candidate = new CandidateVersion(CANDIDATE, TENANT, SESSION, Optional.empty(),
                source.reference(), source.hash(), dependency.reference(), dependency.hash(),
                toolchain.reference(), toolchain.hash(), CandidateVersionStatus.GENERATED, ORDER, NOW);
        CandidateVersion base;
        CodingWorkerInputManifest input;
        WorkOrder order;
        VerificationRun run;
        boolean rejectInput;
        int actionCalls;
        int verifierCalls;
        final BuildSession session = new BuildSession(SESSION, TENANT, new ProjectId("profile-project"),
                ProductLineId.AGENT_PACK, "request", "principal", BuildSessionStatus.VERIFYING,
                BuildSessionPhase.TEST, ref("requirements"), hash("requirements"), Optional.empty(),
                Optional.of("coding-worker"), Optional.empty(), Optional.of(CANDIDATE), Optional.of(source.hash()),
                Optional.empty(), 0, 2, NOW.minusSeconds(60), NOW.plusSeconds(600), Optional.empty(),
                Optional.empty(), Optional.empty(), 0, NOW.minusSeconds(60), NOW);
        final CandidateVersionRepository candidates = proxy(CandidateVersionRepository.class, (name, args) -> switch (name) {
            case "find" -> Optional.ofNullable(CANDIDATE.equals(args[1]) ? candidate : base);
            default -> throw new UnsupportedOperationException(name);
        });
        final BuildSessionRepository sessions = proxy(BuildSessionRepository.class, (name, args) -> switch (name) {
            case "find" -> Optional.of(session);
            default -> throw new UnsupportedOperationException(name);
        });
        final VerificationRunRepository runs = proxy(VerificationRunRepository.class, (name, args) -> switch (name) {
            case "find" -> Optional.of(run);
            case "compareAndSet" -> { if (!run.equals(args[0])) yield false; run = (VerificationRun) args[1]; yield true; }
            default -> throw new UnsupportedOperationException(name);
        });
        final AgentPackGenerationVerificationProfiles profiles;

        Fixture() {
            MaintenanceInvestigationProductContract.artifacts(TENANT).forEach(artifacts::store);
            var contract = MaintenanceInvestigationProductContract.lock();
            var api = MaintenanceInvestigationProductContract.apiSignatureIndexLock();
            var matrix = MaintenanceInvestigationProductContract.requirementTestMatrixLock();
            input = new CodingWorkerInputManifest(CodingWorkerInputManifest.SCHEMA_VERSION, ORDER, SESSION,
                    "flower", "0.3.3", skill.reference(), skill.hash(), dependency.reference(), dependency.hash(),
                    toolchain.reference(), toolchain.hash(), api.reference(), api.hash(), contract.reference(),
                    contract.hash(), MAINTENANCE, matrix.reference(), matrix.hash(),
                    CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID, Optional.empty());
            var manifest = artifacts.add("strict-input-fixture-token");
            order = new WorkOrder(ORDER, TENANT, SESSION, BuildSessionPhase.GENERATE_CANDIDATE.id(), "generate",
                    1, Optional.empty(), Optional.empty(), Optional.empty(), skill.reference(), skill.hash(),
                    manifest.reference(), manifest.hash(), "workspace", List.of("src"), List.of("src"), Set.of(),
                    "schema", "1", ref("policy"), NOW.plusSeconds(600), 1, "key", WorkOrderCreatorType.SERVICE,
                    "factory", NOW);
            var orders = proxy(WorkOrderRepository.class, (name, args) -> switch (name) {
                case "find" -> { assertEquals(candidate.tenantId(), args[0]); assertEquals(candidate.createdByWorkOrderId(), args[1]); yield Optional.ofNullable(order); }
                default -> throw new UnsupportedOperationException(name);
            });
            var decoder = proxy(WorkerProtocolArtifactDecoder.class, (name, args) -> {
                if (!"decodeInputManifest".equals(name)) throw new UnsupportedOperationException(name);
                if (rejectInput) throw new IllegalArgumentException("unknown or duplicate manifest field");
                assertArrayEquals("strict-input-fixture-token".getBytes(StandardCharsets.UTF_8), (byte[]) args[0]);
                return input;
            });
            profiles = new AgentPackGenerationVerificationProfiles(orders, new WorkerProtocolArtifacts(artifacts, decoder), candidates);
            run = new VerificationRun(new VerificationRunId("profile-run"), TENANT, SESSION, CANDIDATE,
                    source.hash(), MAINTENANCE, toolchain.hash(), fixture.hash(), VerificationRunStatus.REQUESTED,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), 0, NOW, NOW);
        }

        void withRepair() {
            var parentId = new CandidateId("parent");
            base = copy(candidate, "candidateId", parentId);
            candidate = copy(candidate, "parentCandidateId", Optional.of(parentId));
            order = copy(order, "candidateId", Optional.of(parentId));
            var finding = artifacts.add("finding");
            input = copy(input, "repairLock", Optional.of(new CodingWorkerRepairLock(parentId, base.sourceHash(),
                    finding.reference(), finding.hash(), List.of("src/Agent.java"), 1, 2)));
        }

        ActionBackedVerificationRunLauncher launcher() {
            return new ActionBackedVerificationRunLauncher(requested -> requested, (proposal, context) -> {
                actionCalls++;
                return ActionExecutionResult.accepted("ACCEPTED", Map.of());
            }, fixture.hash(), profiles);
        }

        VerificationRunPolicyGate policy() { return new VerificationRunPolicyGate(runs, candidates, profiles); }
        VerificationRunPreExecutionGuard guard() {
            return new VerificationRunPreExecutionGuard(sessions, candidates, runs, fixture.hash(),
                    Clock.fixed(NOW, ZoneOffset.UTC), profiles);
        }
        VerificationExecutionService service(AgentPackGenerationVerificationProfiles selected) {
            return new VerificationExecutionService(runs, candidates, artifacts, request -> {
                verifierCalls++;
                assertEquals(MAINTENANCE, request.gateProfile());
                return new VerificationResult(VerificationStatus.PASSED, VerificationDisposition.REVIEW_ELIGIBLE,
                        List.of(VerificationStableCodes.VERIFIED), result.reference(), result.hash(), List.of(result.reference()));
            }, Clock.fixed(NOW, ZoneOffset.UTC), fixture.reference(), selected);
        }
        ActionProposal proposal() {
            var input = new VerificationRunInput(run.verificationRunId(), CANDIDATE, 0);
            return ActionProposal.builder(VerificationRunAction.ACTION_ID).proposalId("profile-proposal")
                    .requestChannel(ActionRequestChannel.INTERNAL).proposerType(ActionProposerType.SERVICE)
                    .requesterId("factory-verifier").input(input.toMap())
                    .idempotencyKey(VerificationRunIdempotencyKeys.derive(run, candidate, 0)).build();
        }
        ExecutionContext context(boolean permitted) {
            return new ExecutionContext(TENANT.value(), "factory-verifier", "profile-action", "trace",
                    Map.of("actor.permissions", permitted ? Set.of(VerificationRunAction.PERMISSION) : Set.of(),
                            "resource.type", VerificationRunAction.RESOURCE_TYPE, "resource.id", CANDIDATE.value()));
        }
    }

    private static final class MemoryArtifacts implements ArtifactStore {
        final Map<ArtifactReference, Artifact> values = new HashMap<>();
        CertificationArtifactLock add(String text) {
            var lock = new CertificationArtifactLock(ref(text), hash(text));
            store(new Artifact(TENANT, lock.reference(), lock.hash(), "application/json", text.getBytes(StandardCharsets.UTF_8)));
            return lock;
        }
        void corrupt(ArtifactReference reference) {
            var previous = values.get(reference);
            store(new Artifact(TENANT, reference, previous.contentHash(), previous.mediaType(), new byte[] {1, 2, 3}));
        }
        public ArtifactReference store(Artifact artifact) { values.put(artifact.reference(), artifact); return artifact.reference(); }
        public Optional<Artifact> find(TenantId tenant, ArtifactReference reference) {
            return Optional.ofNullable(values.get(reference)).filter(value -> value.tenantId().equals(tenant));
        }
    }

    private interface Call { Object invoke(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (instance, method, args) -> call.invoke(method.getName(), args)));
    }
    private static ArtifactReference ref(String value) { return new ArtifactReference("artifact:" + value); }
    private static ContentHash hash(String text) { return sha256(text.getBytes(StandardCharsets.UTF_8)); }
    private static ContentHash sha256(byte[] content) {
        try { return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    @SuppressWarnings("unchecked")
    private static <T extends Record> T copy(T original, Object... replacements) {
        try {
            var components = original.getClass().getRecordComponents();
            var types = new Class<?>[components.length];
            var values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                values[i] = components[i].getAccessor().invoke(original);
                for (int j = 0; j < replacements.length; j += 2) {
                    if (components[i].getName().equals(replacements[j])) values[i] = replacements[j + 1];
                }
            }
            return (T) original.getClass().getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
