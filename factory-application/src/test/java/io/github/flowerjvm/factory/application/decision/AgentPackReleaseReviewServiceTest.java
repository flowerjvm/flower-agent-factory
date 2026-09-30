package io.github.flowerjvm.factory.application.decision;

import static org.junit.jupiter.api.Assertions.*;

import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.candidate.*;
import io.github.flowerjvm.factory.application.certification.AgentPackProductContract;
import io.github.flowerjvm.factory.application.verification.*;
import io.github.flowerjvm.factory.application.work.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.factory.contracts.verification.*;
import io.github.flowerjvm.factory.contracts.worker.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AgentPackReleaseReviewServiceTest {
    static final TenantId TENANT = new TenantId("review-tenant");
    static final BuildSessionId SESSION = new BuildSessionId("review-session");
    static final CandidateId CANDIDATE = new CandidateId("review-candidate");
    static final WorkOrderId ORDER = new WorkOrderId("review-order");
    static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    static final Duration WINDOW = Duration.ofMinutes(5);
    static final String MAINTENANCE = MaintenanceInvestigationProductContract.GATE_PROFILE;
    static final String PR4 = ActionBackedVerificationRunLauncher.GATE_PROFILE;

    @Test
    void opensOnlyExactIndependentlyVerifiedMaintenanceCandidateWithoutMakingHumanDecision() {
        var f = new Fixture();
        var result = f.service().ensureReleaseReview(TENANT, SESSION);
        var point = result.decisionPoint();
        assertEquals(AgentPackReleaseReviewResult.Disposition.CREATED, result.disposition());
        assertEquals(DecisionPointStatus.OPEN, point.status());
        assertEquals(DecisionPoint.RELEASE_REVIEW_TYPE, point.type());
        assertEquals("CANDIDATE", point.subjectType());
        assertEquals(CANDIDATE.value(), point.subjectId());
        assertEquals(f.candidate.sourceHash(), point.subjectHash());
        assertEquals(Set.of("factory.agent.release.approve"), point.requiredPermissions());
        assertEquals("factory.release-review-options.v1", point.optionsSchemaId());
        assertEquals(NOW.plus(WINDOW), point.dueAt());
        assertTrue(point.terminalDecisionId().isEmpty());
        assertTrue(point.decidedAt().isEmpty());
        assertEquals(0, point.version());
        assertEquals(1, f.created);
        assertTrue(f.evidenceReads >= 2);
        String question = new String(f.artifacts.get(point.questionArtifactRef()).content(), StandardCharsets.UTF_8);
        assertTrue(question.contains(MAINTENANCE));
        assertTrue(question.contains(f.run.resultManifestHash().orElseThrow().sha256()));
        assertTrue(question.contains(f.artifacts.get(f.candidate.sourceManifestRef()).contentHash().sha256()));
        assertNotEquals(f.candidate.sourceHash(), f.artifacts.get(f.candidate.sourceManifestRef()).contentHash());
    }

    @Test
    void restartAndLaterRetryKeepOriginalQuestionIdAndDeadline() {
        var f = new Fixture();
        var first = f.service().ensureReleaseReview(TENANT, SESSION);
        f.now = NOW.plusSeconds(30);
        var retry = f.service().ensureReleaseReview(TENANT, SESSION);
        assertEquals(first.decisionPoint(), retry.decisionPoint());
        assertEquals(AgentPackReleaseReviewResult.Disposition.EXISTING_EXACT, retry.disposition());
        assertEquals(1, f.created);
    }

    @Test
    void sessionDeadlineCapsReviewPolicyAndExpiredReviewIsNeverReopened() {
        var f = new Fixture();
        f.session = copy(f.session, "deadlineAt", NOW.plusSeconds(100));
        assertEquals(NOW.plusSeconds(100), f.service().ensureReleaseReview(TENANT, SESSION).decisionPoint().dueAt());
        f.now = NOW.plusSeconds(100);
        assertThrows(IllegalArgumentException.class, () -> f.service().ensureReleaseReview(TENANT, SESSION));
        assertEquals(1, f.created);
        var g = new Fixture();
        g.service().ensureReleaseReview(TENANT, SESSION);
        g.now = NOW.plus(WINDOW);
        assertThrows(IllegalArgumentException.class, () -> g.service().ensureReleaseReview(TENANT, SESSION));
        assertEquals(1, g.created);
    }

    @Test
    void legacyGenerationRemainsSupportedButCannotDowngradeMaintenance() {
        var f = new Fixture();
        f.artifactStore.store(AgentPackProductContract.artifact(TENANT));
        f.input = copy(f.input, "productContractBundleRef", AgentPackProductContract.lock().reference(),
                "productContractBundleHash", AgentPackProductContract.lock().hash(), "gateProfile", PR4);
        f.run = copy(f.run, "gateProfile", PR4);
        assertEquals(DecisionPointStatus.OPEN, f.service().ensureReleaseReview(TENANT, SESSION).decisionPoint().status());
    }

    @Test
    void changedVerificationOrSourceArtifactCannotReplaceAnExistingCandidateReview() {
        var f = new Fixture();
        var original = f.service().ensureReleaseReview(TENANT, SESSION).decisionPoint();
        f.run = copy(f.run, "verificationRunId", new VerificationRunId("different-run"));
        assertThrows(IllegalArgumentException.class, () -> f.service().ensureReleaseReview(TENANT, SESSION));
        assertEquals(original, f.stored);
        var g = new Fixture();
        g.service().ensureReleaseReview(TENANT, SESSION);
        // Even self-consistent replacement bytes at the same source reference cannot reuse a
        // review. The real independent validator also rejects changed source evidence.
        g.artifacts.put(g.candidate.sourceManifestRef(), new Artifact(TENANT,
                g.candidate.sourceManifestRef(), hash("changed-source"), "application/json", bytes("changed-source")));
        assertThrows(IllegalArgumentException.class, () -> g.service().ensureReleaseReview(TENANT, SESSION));
        assertEquals(1, g.created);
    }

    @Test
    void cancellationBetweenEvidenceAndAtomicOpenDoesNotPublishReview() {
        var f = new Fixture();
        f.beforeTransaction = () -> f.session = f.session.requestCancellation(NOW);
        assertThrows(IllegalArgumentException.class, () -> f.service().ensureReleaseReview(TENANT, SESSION));
        assertEquals(0, f.created);
        assertNull(f.stored);
    }

    @Test
    void policyWindowChangesCannotWeakenAnExistingReview() {
        var f = new Fixture();
        f.service().ensureReleaseReview(TENANT, SESSION);
        f.stored = copy(f.stored, "requiredPermissions", Set.of());
        assertThrows(IllegalArgumentException.class, () -> f.service().ensureReleaseReview(TENANT, SESSION));
        assertEquals(1, f.created);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAuthorities")
    void invalidAuthorityNeverReachesAtomicOpen(String label, Consumer<Fixture> mutate) {
        var f = new Fixture();
        mutate.accept(f);
        assertThrows(RuntimeException.class, () -> f.service().ensureReleaseReview(TENANT, SESSION), label);
        assertEquals(0, f.transactionCalls, label);
        assertNull(f.stored);
    }

    static Stream<Arguments> invalidAuthorities() {
        Map<String, Consumer<Fixture>> cases = new LinkedHashMap<>();
        cases.put("foreign tenant", f -> f.session = copy(f.session, "tenantId", new TenantId("other")));
        cases.put("wrong session", f -> f.session = copy(f.session, "buildSessionId", new BuildSessionId("other")));
        cases.put("wrong product line", f -> f.session = copy(f.session, "productLineId", ProductLineId.REFERENCE_ASSEMBLY));
        cases.put("wrong phase", f -> f.session = copy(f.session, "currentPhase", BuildSessionPhase.TEST));
        cases.put("wrong status", f -> f.session = copy(f.session, "status", BuildSessionStatus.VERIFYING));
        cases.put("cancelled", f -> f.session = f.session.requestCancellation(NOW));
        cases.put("deadline reached", f -> f.now = f.session.deadlineAt());
        cases.put("candidate identity changed", f -> f.candidate = copy(f.candidate, "candidateId", new CandidateId("other")));
        cases.put("candidate source hash changed", f -> f.candidate = copy(f.candidate, "sourceHash", hash("other")));
        cases.put("foreign verification tenant", f -> f.run = copy(f.run, "tenantId", new TenantId("other")));
        cases.put("wrong verification candidate", f -> f.run = copy(f.run, "candidateHash", hash("other")));
        cases.put("wrong gate downgrade", f -> f.run = copy(f.run, "gateProfile", PR4));
        cases.put("failed gate", f -> f.run = copy(f.run, "status", VerificationRunStatus.FAILED,
                "disposition", Optional.of(VerificationDisposition.REPAIR_REQUIRED)));
        cases.put("legacy sentinel evidence", f -> f.run = copy(f.run, "resultManifestHash", Optional.of(VerificationRun.LEGACY_RESULT_MANIFEST_HASH)));
        cases.put("independent evidence rejected", f -> f.evidencePassed = false);
        cases.put("owner pending", f -> f.owner = VerificationActionEvidenceOwner.Assessment.pending());
        cases.put("owner invalid", f -> f.owner = new VerificationActionEvidenceOwner.Assessment(
                VerificationActionEvidenceOwner.Status.INVALID_TERMINAL, "mismatch"));
        cases.put("source artifact missing", f -> f.artifacts.remove(f.candidate.sourceManifestRef()));
        cases.put("source artifact corrupt", f -> f.artifacts.put(f.candidate.sourceManifestRef(), new Artifact(
                TENANT, f.candidate.sourceManifestRef(), hash("source"), "application/json", bytes("corrupt"))));
        cases.put("missing generation contract", f -> f.artifacts.remove(MaintenanceInvestigationProductContract.lock().reference()));
        return cases.entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    private static final class Fixture {
        final Map<ArtifactReference, Artifact> artifacts = new HashMap<>();
        final ArtifactStore artifactStore = new ArtifactStore() {
            public ArtifactReference store(Artifact artifact) {
                artifacts.put(artifact.reference(), artifact); return artifact.reference();
            }
            public Optional<Artifact> find(TenantId tenant, ArtifactReference reference) {
                return Optional.ofNullable(artifacts.get(reference)).filter(a -> a.tenantId().equals(tenant));
            }
        };
        Instant now = NOW;
        final Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        };
        CandidateVersion candidate = new CandidateVersion(CANDIDATE, TENANT, SESSION, Optional.empty(),
                ref("source"), hash("candidate-tree"), ref("dependency"), hash("dependency"),
                ref("toolchain"), hash("toolchain"), CandidateVersionStatus.GENERATED, ORDER, NOW.minusSeconds(10));
        BuildSession session = new BuildSession(SESSION, TENANT, new ProjectId("review-project"), ProductLineId.AGENT_PACK,
                "review-request", "principal", BuildSessionStatus.WAITING_RELEASE_REVIEW, BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                ref("requirements"), hash("requirements"), Optional.empty(), Optional.of("worker"), Optional.empty(),
                Optional.of(CANDIDATE), Optional.of(candidate.sourceHash()), Optional.empty(), 0, 2,
                NOW.minusSeconds(60), NOW.plusSeconds(600), Optional.empty(), Optional.empty(), Optional.empty(),
                3, NOW.minusSeconds(60), NOW);
        VerificationRun run = new VerificationRun(new VerificationRunId("review-run"), TENANT, SESSION, CANDIDATE,
                candidate.sourceHash(), MAINTENANCE, candidate.toolchainLockHash(), hash("fixture"),
                VerificationRunStatus.PASSED, Optional.of(ref("result")), Optional.of(hash("result")),
                Optional.of(VerificationStableCodes.VERIFIED), Optional.of(VerificationDisposition.REVIEW_ELIGIBLE),
                Optional.of(NOW.minusSeconds(5)), Optional.of(NOW.minusSeconds(1)), 2, NOW.minusSeconds(5), NOW.minusSeconds(1));
        CodingWorkerInputManifest input;
        final AgentPackGenerationVerificationProfiles profiles;
        final AgentPackReleaseReviewPolicy policy = new AgentPackReleaseReviewPolicy(WINDOW);
        boolean evidencePassed = true;
        VerificationActionEvidenceOwner.Assessment owner = VerificationActionEvidenceOwner.Assessment.canonical();
        int created;
        int transactionCalls;
        int evidenceReads;
        DecisionPoint stored;
        Runnable beforeTransaction = () -> {};
        final BuildSessionRepository sessions = proxy(BuildSessionRepository.class, (method, args) -> switch (method) {
            case "find" -> Optional.of(session);
            default -> throw new UnsupportedOperationException(method);
        });
        final CandidateVersionRepository candidates = proxy(CandidateVersionRepository.class, (method, args) -> switch (method) {
            case "find" -> Optional.of(candidate);
            default -> throw new UnsupportedOperationException(method);
        });
        final VerificationRunRepository runs = proxy(VerificationRunRepository.class, (method, args) -> switch (method) {
            case "findLatestForCandidate" -> Optional.of(run);
            default -> throw new UnsupportedOperationException(method);
        });

        Fixture() {
            for (String name : List.of("source", "dependency", "toolchain", "skill", "input")) {
                artifactStore.store(new Artifact(TENANT, ref(name), hash(name), "application/json", bytes(name)));
            }
            MaintenanceInvestigationProductContract.artifacts(TENANT).forEach(artifactStore::store);
            var api = MaintenanceInvestigationProductContract.apiSignatureIndexLock();
            var contract = MaintenanceInvestigationProductContract.lock();
            var matrix = MaintenanceInvestigationProductContract.requirementTestMatrixLock();
            input = new CodingWorkerInputManifest(CodingWorkerInputManifest.SCHEMA_VERSION, ORDER, SESSION,
                    "flower", "0.3.3", ref("skill"), hash("skill"), ref("dependency"), hash("dependency"),
                    ref("toolchain"), hash("toolchain"), api.reference(), api.hash(), contract.reference(), contract.hash(),
                    MAINTENANCE, matrix.reference(), matrix.hash(), CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID, Optional.empty());
            WorkOrder order = new WorkOrder(ORDER, TENANT, SESSION, BuildSessionPhase.GENERATE_CANDIDATE.id(), "generate",
                    1, Optional.empty(), Optional.empty(), Optional.empty(), ref("skill"), hash("skill"), ref("input"), hash("input"),
                    "workspace", List.of("src"), List.of("src"), Set.of(), "schema", "1", ref("policy"), NOW.plusSeconds(600),
                    1, "key", WorkOrderCreatorType.SERVICE, "factory", NOW.minusSeconds(10));
            WorkOrderRepository orders = proxy(WorkOrderRepository.class, (method, args) -> {
                if (!"find".equals(method)) throw new UnsupportedOperationException(method);
                return Optional.of(order);
            });
            WorkerProtocolArtifactDecoder decoder = proxy(WorkerProtocolArtifactDecoder.class, (method, args) -> {
                if (!"decodeInputManifest".equals(method)) throw new UnsupportedOperationException(method);
                assertArrayEquals(bytes("input"), (byte[]) args[0]);
                return input;
            });
            profiles = new AgentPackGenerationVerificationProfiles(orders, new WorkerProtocolArtifacts(artifactStore, decoder), candidates);
        }

        AgentPackReleaseReviewService service() {
            return new AgentPackReleaseReviewService(sessions, candidates, runs, profiles, verification -> {
                evidenceReads++; return evidencePassed;
            }, verification -> owner, artifactStore, (expected, expectedCandidate, expectedVerification, requested) -> {
                transactionCalls++;
                beforeTransaction.run();
                if (!session.equals(expected) || !candidate.equals(expectedCandidate) || !run.equals(expectedVerification)) {
                    throw new IllegalArgumentException(AgentPackReleaseReviewPolicy.CONFLICT);
                }
                policy.requireRequested(session, candidate, run, requested, now);
                if (stored != null) {
                    policy.requireExisting(session, candidate, requested, stored, now);
                    return new AgentPackReleaseReviewResult(AgentPackReleaseReviewResult.Disposition.EXISTING_EXACT, stored);
                }
                stored = requested;
                created++;
                return new AgentPackReleaseReviewResult(AgentPackReleaseReviewResult.Disposition.CREATED, stored);
            }, clock, WINDOW);
        }
    }

    private interface Call { Object invoke(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (instance, method, args) -> call.invoke(method.getName(), args)));
    }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static ArtifactReference ref(String value) { return new ArtifactReference("artifact:review-test:" + value); }
    static ContentHash hash(String value) { return AgentPackReleaseReviewPolicy.hash(bytes(value)); }
    @SuppressWarnings("unchecked")
    static <T extends Record> T copy(T original, Object... replacements) {
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
