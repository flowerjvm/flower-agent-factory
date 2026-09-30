package io.github.flowerjvm.factory.infrastructure.verification;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.action.*;
import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.candidate.*;
import io.github.flowerjvm.factory.application.production.*;
import io.github.flowerjvm.factory.application.verification.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.infrastructure.production.JacksonAgentPackProductionCodec;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.run.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Deterministic receipt contract tests. These do not claim a sandbox run or physical database-corruption monitoring. */
class ArtifactVerificationReviewEvidenceTest {
    private static final Instant NOW = Instant.parse("2026-09-06T04:00:00Z");
    private static final TenantId TENANT = new TenantId("review-evidence-tenant");
    private static final BuildSessionId SESSION = new BuildSessionId("review-evidence-session");
    private static final CandidateId CANDIDATE = new CandidateId("review-evidence-candidate");
    private static final VerificationRunId RUN = new VerificationRunId("review-evidence-run");

    @Test
    void recorderCallsFullGateOffTickAndReaderLoadsOnlyPlanAndReceiptAfterCanonicalCompletion() {
        Fixture f = new Fixture(); var service = f.service();
        var lock = service.validateAndRecord(f.intent, f.action, f.run).orElseThrow();
        assertEquals(1, f.fullCalls); assertTrue(f.reads.contains(f.candidate.sourceManifestRef()));
        assertFalse(service.isReviewEligible(f.run)); // A staged receipt is not an owned terminal result.
        f.complete(lock); f.reads.clear(); f.forbidLargeReads = true;
        assertTrue(service.isReviewEligible(f.run)); assertEquals(1, f.fullCalls);
        assertEquals(Set.of(f.planRef(), lock.reference()), new HashSet<>(f.reads));
        assertFalse(f.reads.contains(f.candidate.sourceManifestRef()));
        assertFalse(f.reads.contains(f.run.resultManifestRef().orElseThrow()));
    }

    @Test
    void deterministicReceiptReusesImmutableBytesAcrossRestartBeforeActionCompletionWithoutReexecutingVerifier() {
        Fixture f = new Fixture();
        var first = f.service().validateAndRecord(f.intent, f.action, f.run).orElseThrow();
        var second = f.service().validateAndRecord(f.intent, f.action, f.run).orElseThrow();
        assertEquals(first, second); assertEquals(2, f.fullCalls);
        assertEquals(1, f.receiptReferences().size());
        f.complete(second); f.forbidLargeReads = true;
        assertTrue(f.service().isReviewEligible(f.run));
    }

    @Test
    void fullGateFailureCannotCreateEligibilityOrPromotePassedDomainToAnOwnedReceipt() {
        Fixture f = new Fixture(); f.fullAllowed = false;
        assertThrows(IllegalArgumentException.class, () -> f.service().validateAndRecord(f.intent, f.action, f.run));
        assertTrue(f.receiptReferences().isEmpty()); assertFalse(f.service().isReviewEligible(f.run));
        assertEquals(VerificationRunStatus.PASSED, f.run.status());
        assertEquals(ActionRunStatus.WAITING_EXTERNAL, f.action.status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "deadline", "claim", "lease", "action", "candidate", "verification", "plan", "repair-round"})
    void changeDuringFullReadFailsClosedBeforeReturningAnyReceipt(String change) {
        Fixture f = new Fixture(); f.duringFull = () -> {
            switch (change) {
                case "cancel" -> f.session = f.session.requestCancellation(NOW);
                case "deadline" -> f.now = f.session.deadlineAt();
                case "claim" -> f.intent = copy(f.intent, "claimToken", Optional.of("new-claim"), "version", f.intent.version() + 1);
                case "lease" -> f.now = f.intent.leaseUntil().orElseThrow();
                case "action" -> f.action = f.action.toBuilder().status(ActionRunStatus.CANCELLED).version(f.action.version() + 1).build();
                case "candidate" -> f.candidate = copy(f.candidate, "sourceHash", hash("changed"));
                case "verification" -> f.run = copy(f.run, "resultManifestHash", Optional.of(hash("changed")));
                case "plan" -> f.replacePlan(copy(f.plan, "workspaceRef", "changed-workspace"));
                case "repair-round" -> f.session = copy(f.session, "repairRound", 1);
                default -> throw new AssertionError(change);
            }
        };
        var originalRun = f.run; var originalIntent = f.intent; var originalAction = f.action;
        assertThrows(IllegalArgumentException.class, () -> f.service().validateAndRecord(originalIntent, originalAction, originalRun));
        assertTrue(f.receiptReferences().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-receipt", "receipt-bytes", "missing-field", "wrong-hash", "legacy-output", "action-failed",
            "intent-pending", "intent-code", "owner", "trace", "permission", "current-candidate", "repair-round", "plan-hash", "latest-run"})
    void readerRejectsMissingCorruptOrUnownedReceiptsWithoutFallingBackToFullSourceReads(String change) {
        Fixture f = new Fixture(); var lock = f.service().validateAndRecord(f.intent, f.action, f.run).orElseThrow();
        f.complete(lock);
        switch (change) {
            case "missing-receipt" -> f.rows.remove(lock.reference());
            case "receipt-bytes" -> {
                Artifact old = f.rows.get(lock.reference());
                f.rows.put(lock.reference(), new Artifact(TENANT, old.reference(), old.contentHash(), old.mediaType(), "{}".getBytes(StandardCharsets.UTF_8)));
            }
            case "missing-field" -> f.outputWithout(VerificationReviewEvidenceOutput.HASH_KEY);
            case "wrong-hash" -> {
                var output = new HashMap<>(f.action.result().output()); output.put(VerificationReviewEvidenceOutput.HASH_KEY, hash("wrong").sha256());
                f.action = f.action.toBuilder().result(ActionExecutionResult.succeeded(output)).build();
            }
            case "legacy-output" -> f.action = f.action.toBuilder().result(ActionExecutionResult.succeeded(f.baseOutput())).build();
            case "action-failed" -> f.action = f.action.toBuilder().status(ActionRunStatus.FAILED).build();
            case "intent-pending" -> f.intent = f.pending;
            case "intent-code" -> f.intent = copy(f.intent, "lastCode", Optional.of("UNTRUSTED_COMPLETE"));
            case "owner" -> f.action = f.action.toBuilder().userId("different-principal").build();
            case "trace" -> f.action = f.action.toBuilder().traceId("different-trace").build();
            case "permission" -> f.action = f.action.toBuilder().contextMetadata(Map.of("actor.permissions", List.of(),
                    "resource.type", VerificationRunAction.RESOURCE_TYPE, "resource.id", CANDIDATE.value())).build();
            case "current-candidate" -> f.session = copy(f.session, "currentCandidateId", Optional.of(new CandidateId("another")));
            case "repair-round" -> f.session = copy(f.session, "repairRound", 1);
            case "plan-hash" -> f.replacePlan(copy(f.plan, "workspaceRef", "changed-workspace"));
            case "latest-run" -> f.latest = copy(f.run, "verificationRunId", new VerificationRunId("newer-run"));
            default -> throw new AssertionError(change);
        }
        f.forbidLargeReads = true; f.reads.clear();
        assertFalse(f.service().isReviewEligible(f.run)); assertEquals(1, f.fullCalls);
    }

    @Test
    void receiptRemainsHistoricalEvidenceThroughHumanReviewCertificationCompletionAndPastOriginalDeadline() {
        Fixture f = new Fixture(); var lock = f.service().validateAndRecord(f.intent, f.action, f.run).orElseThrow(); f.complete(lock);
        f.forbidLargeReads = true;
        for (BuildSessionPhase phase : List.of(BuildSessionPhase.HUMAN_RELEASE_REVIEW, BuildSessionPhase.CERTIFY, BuildSessionPhase.COMPLETE)) {
            BuildSessionStatus status = switch (phase) {
                case HUMAN_RELEASE_REVIEW -> BuildSessionStatus.WAITING_RELEASE_REVIEW;
                case CERTIFY -> BuildSessionStatus.CERTIFYING;
                default -> BuildSessionStatus.SUCCEEDED;
            };
            f.session = copy(f.session, "currentPhase", phase, "status", status, "version", f.session.version() + 1,
                    "currentCertificationId", phase == BuildSessionPhase.COMPLETE ? Optional.of(new CertificationId("issued")) : Optional.empty(),
                    "terminalCode", phase == BuildSessionPhase.COMPLETE ? Optional.of("COMPLETE") : Optional.empty());
            f.now = f.session.deadlineAt().plusSeconds(48 * 3600);
            assertTrue(f.service().isReviewEligible(f.run));
        }
        assertEquals(1, f.fullCalls);
    }

    @Test
    void legacyOrdersKeepTheirFullValidatorAndNoNewReceiptOutput() {
        Fixture f = new Fixture(); f.rows.remove(f.planRef());
        assertTrue(f.service().validateAndRecord(f.intent, f.action, f.run).isEmpty());
        assertEquals(0, f.fullCalls); assertTrue(f.service().isReviewEligible(f.run)); assertEquals(1, f.fullCalls);
        assertTrue(f.receiptReferences().isEmpty());
    }

    @Test
    void lostPlanAfterOwnedReceiptCannotSilentlyDowngradeToLegacyFullValidation() {
        Fixture f = new Fixture(); var lock = f.service().validateAndRecord(f.intent, f.action, f.run).orElseThrow(); f.complete(lock);
        f.rows.remove(f.planRef()); assertFalse(f.service().isReviewEligible(f.run)); assertEquals(1, f.fullCalls);
    }

    @Test
    void declinedDomainKeepsLegacyFailureOutputAndNeverGetsAReviewReceipt() {
        Fixture f = new Fixture(); f.run = copy(f.run, "status", VerificationRunStatus.FAILED,
                "terminalCode", Optional.of("MAVEN_VERIFICATION_FAILED"), "disposition", Optional.of(VerificationDisposition.REPAIR_REQUIRED));
        assertTrue(f.service().validateAndRecord(f.intent, f.action, f.run).isEmpty());
        assertFalse(f.service().isReviewEligible(f.run)); assertEquals(0, f.fullCalls);
    }

    private static final class Fixture {
        final Map<ArtifactReference, Artifact> rows = new HashMap<>(); final List<ArtifactReference> reads = new ArrayList<>();
        final ObjectMapper mapper = new ObjectMapper(); final JacksonAgentPackProductionCodec codec = new JacksonAgentPackProductionCodec(mapper);
        Instant now = NOW; int fullCalls; boolean fullAllowed = true; boolean forbidLargeReads; Runnable duringFull = () -> {};
        final Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
            public Instant instant() { return now; }
        };
        CandidateVersion candidate = new CandidateVersion(CANDIDATE, TENANT, SESSION, Optional.empty(), ref("source"), hash("tree"),
                ref("dependency"), hash("dependency"), ref("toolchain"), hash("toolchain"), CandidateVersionStatus.GENERATED,
                new WorkOrderId("generation"), NOW.minusSeconds(30));
        BuildSession session = new BuildSession(SESSION, TENANT, new ProjectId("review-project"), ProductLineId.AGENT_PACK,
                "request", "factory-builder", BuildSessionStatus.VERIFYING, BuildSessionPhase.TEST,
                MaintenanceInvestigationProductContract.requirementsLock().reference(), MaintenanceInvestigationProductContract.requirementsLock().hash(),
                Optional.of("manager"), Optional.of("coding"), Optional.empty(), Optional.of(CANDIDATE), Optional.of(candidate.sourceHash()),
                Optional.empty(), 0, 2, NOW.minusSeconds(40), NOW.plusSeconds(600), Optional.empty(), Optional.empty(), Optional.empty(),
                5, NOW.minusSeconds(40), NOW.minusSeconds(10));
        AgentPackProductionPlan plan = new AgentPackProductionPlan(AgentPackProductionPlan.SCHEMA_VERSION, TENANT, SESSION,
                MaintenanceProductionRecipe.ID, MaintenanceInvestigationProductContract.requirementsLock(), "flower", "0.3.3",
                lock("skill"), lock("dependency"), lock("toolchain"), MaintenanceInvestigationProductContract.lock(),
                MaintenanceInvestigationProductContract.apiSignatureIndexLock(), MaintenanceInvestigationProductContract.requirementTestMatrixLock(),
                MaintenanceInvestigationProductContract.GATE_PROFILE, lock("policy"), "workspace",
                new AgentPackProductionPlan.WorkerBinding("manager", "1", new WorkerCapabilities(Set.of())),
                new AgentPackProductionPlan.WorkerBinding("coding", "1", new WorkerCapabilities(Set.of())));
        VerificationRun run = new VerificationRun(RUN, TENANT, SESSION, CANDIDATE, candidate.sourceHash(), plan.gateProfile(),
                candidate.toolchainLockHash(), hash("fixture"), VerificationRunStatus.REQUESTED, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW.minusSeconds(9), NOW.minusSeconds(9))
                .start(NOW.minusSeconds(8)).complete(VerificationRunStatus.PASSED, ref("result"), hash("result"), "VERIFIED",
                        VerificationDisposition.REVIEW_ELIGIBLE, NOW.minusSeconds(2));
        VerificationRun latest;
        final VerificationDispatchIntent pending = VerificationDispatchIntent.pending("operation", TENANT, RUN, CANDIDATE,
                0, "action-owner", VerificationAttemptTokens.hash("attempt"), session.deadlineAt(), NOW.minusSeconds(8));
        VerificationDispatchIntent intent = pending.claim("claim", NOW.minusSeconds(5), Duration.ofMinutes(5));
        ActionRun action = ActionRun.builder().runId("action-owner").version(4).tenantId(TENANT.value()).userId("factory-verifier")
                .traceId("verification:" + RUN.value()).contextMetadata(Map.of("actor.permissions", List.of(VerificationRunAction.PERMISSION),
                        "resource.type", VerificationRunAction.RESOURCE_TYPE, "resource.id", CANDIDATE.value()))
                .actionId(VerificationRunAction.ACTION_ID).proposalId("proposal").requesterId("factory-verifier")
                .requestChannel(ActionRequestChannel.INTERNAL).proposerType(ActionProposerType.SERVICE)
                .input(new VerificationRunInput(RUN, CANDIDATE, 0).toMap()).duplicateKey(VerificationRunIdempotencyKeys.derive(run, candidate, 0))
                .status(ActionRunStatus.WAITING_EXTERNAL).currentStage("WAITING_EXTERNAL").attemptToken("attempt")
                .externalOperationId("operation").createdAt(NOW.minusSeconds(8)).updatedAt(NOW.minusSeconds(7)).build();
        final ArtifactStore store = new ArtifactStore() {
            public ArtifactReference store(Artifact artifact) {
                var previous = rows.putIfAbsent(artifact.reference(), artifact);
                if (previous != null && !previous.contentHash().equals(artifact.contentHash())) throw new IllegalArgumentException("immutable collision");
                return artifact.reference();
            }
            public Optional<Artifact> find(TenantId tenant, ArtifactReference reference) {
                reads.add(reference);
                if (forbidLargeReads && !reference.equals(planRef())
                        && !reference.value().startsWith(VerificationReviewEvidenceOutput.REFERENCE_PREFIX)) {
                    throw new AssertionError("fast reader loaded full evidence: " + reference.value());
                }
                return TENANT.equals(tenant) ? Optional.ofNullable(rows.get(reference)) : Optional.empty();
            }
        };
        Fixture() {
            replacePlan(plan);
            byte[] source = "{\"manifest\":\"test-double\"}".getBytes(StandardCharsets.UTF_8);
            rows.put(candidate.sourceManifestRef(), new Artifact(TENANT, candidate.sourceManifestRef(), hash(source), "application/json", source));
        }
        ArtifactReference planRef() { return AgentPackProductionService.planReference(TENANT, SESSION); }
        void replacePlan(AgentPackProductionPlan next) {
            plan = next; byte[] bytes = codec.writePlan(plan);
            rows.put(planRef(), new Artifact(TENANT, planRef(), hash(bytes), "application/json", bytes));
        }
        Set<ArtifactReference> receiptReferences() {
            var result = new HashSet<ArtifactReference>();
            rows.keySet().stream().filter(ref -> ref.value().startsWith(VerificationReviewEvidenceOutput.REFERENCE_PREFIX)).forEach(result::add);
            return result;
        }
        Map<String, Object> baseOutput() {
            return Map.of("verificationRunId", RUN.value(), "verificationStatus", run.status().name(), "terminalCode", run.terminalCode().orElseThrow(),
                    "resultManifestRef", run.resultManifestRef().orElseThrow().value(), "executedNow", true);
        }
        void complete(CertificationArtifactLock receipt) {
            action = action.toBuilder().status(ActionRunStatus.SUCCEEDED).version(5)
                    .result(ActionExecutionResult.succeeded(VerificationReviewEvidenceOutput.add(baseOutput(), Optional.of(receipt)))).updatedAt(NOW).build();
            intent = intent.complete("claim", VerificationDispatchRunner.COMPLETED, NOW);
        }
        void outputWithout(String field) {
            var output = new HashMap<>(action.result().output()); output.remove(field);
            action = action.toBuilder().result(ActionExecutionResult.succeeded(output)).build();
        }
        ArtifactVerificationReviewEvidence service() {
            return new ArtifactVerificationReviewEvidence(store,
                    proxy(BuildSessionRepository.class, (method, args) -> Optional.of(session)),
                    proxy(CandidateVersionRepository.class, (method, args) -> Optional.of(candidate)),
                    proxy(VerificationRunRepository.class, (method, args) -> Optional.of("findLatestForCandidate".equals(method) && latest != null ? latest : run)),
                    proxy(VerificationDispatchIntentRepository.class, (method, args) -> Optional.of(intent)),
                    proxy(RunStore.class, (method, args) -> Optional.of(action)),
                    verification -> { fullCalls++; if (forbidLargeReads) throw new AssertionError("fast reader called full gate"); duringFull.run(); return fullAllowed; },
                    mapper, clock);
        }
    }
    private interface Call { Object invoke(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, args) -> call.invoke(method.getName(), args)));
    }
    private static ArtifactReference ref(String value) { return new ArtifactReference("artifact:receipt-test:" + value); }
    private static CertificationArtifactLock lock(String value) { return new CertificationArtifactLock(ref(value), hash(value)); }
    private static ContentHash hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    private static ContentHash hash(byte[] value) {
        try { return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    @SuppressWarnings("unchecked")
    private static <T extends Record> T copy(T original, Object... replacements) {
        try {
            var components = original.getClass().getRecordComponents(); var types = new Class<?>[components.length]; var values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType(); values[i] = components[i].getAccessor().invoke(original);
                for (int j = 0; j < replacements.length; j += 2) if (components[i].getName().equals(replacements[j])) values[i] = replacements[j + 1];
            }
            return (T) original.getClass().getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
