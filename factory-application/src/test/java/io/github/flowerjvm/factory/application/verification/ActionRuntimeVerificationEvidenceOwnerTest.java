package io.github.flowerjvm.factory.application.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.flowerjvm.factory.application.action.VerificationRunAction;
import io.github.flowerjvm.factory.application.action.VerificationRunIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.VerificationRunInput;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ActionRuntimeVerificationEvidenceOwnerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final TenantId TENANT = new TenantId("tenant-owner");
    private static final CandidateId CANDIDATE = new CandidateId("candidate-owner");
    private static final VerificationRunId RUN = new VerificationRunId("verification-owner");

    @Test
    void canonicalSucceededActionWinsTheCrashWindowBeforeIntentCompletedCas() {
        CandidateVersion candidate = candidate();
        VerificationRun terminal = terminalRun();
        String token = "attempt-token";
        VerificationDispatchIntent intent = VerificationDispatchIntent.pending(
                        VerificationDispatchOperationIds.derive(new VerificationRunInput(RUN, CANDIDATE, 0)),
                        TENANT, RUN, CANDIDATE, 0, "action-owner", VerificationAttemptTokens.hash(token),
                        NOW.plusSeconds(600), NOW.minusSeconds(2))
                .claim("claim", NOW.minusSeconds(1), Duration.ofMinutes(30));
        InMemoryRunStore store = new InMemoryRunStore();
        store.create(actionRun(candidate, terminal, token, ActionRunStatus.SUCCEEDED,
                ActionExecutionResult.succeeded(output(terminal))));
        var owner = new ActionRuntimeVerificationEvidenceOwner(
                repository(intent), store, candidates(candidate), (tenant, action, key) -> Optional.of("action-owner"));

        assertEquals(
                VerificationActionEvidenceOwner.Status.CANONICAL_SUCCEEDED,
                owner.assess(terminal).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING", "UNCERTAIN"})
    void receiptBearingSucceededActionRemainsPendingUntilItsIntentCompletionIsDurable(String phase) {
        var pending = receiptPendingIntent();
        var running = pending.claim("claim", NOW.minusSeconds(1), Duration.ofMinutes(30));
        var intent = switch (phase) {
            case "PENDING" -> pending;
            case "RUNNING" -> running;
            case "UNCERTAIN" -> running.uncertain("claim", VerificationDispatchRunner.PARK_UNCERTAIN, NOW, NOW.plusSeconds(30));
            default -> throw new AssertionError(phase);
        };
        assertEquals(VerificationActionEvidenceOwner.Status.PENDING, receiptOwner(intent).assess(terminalRun()).status());
    }

    @Test
    void receiptOwnerBecomesCanonicalOnlyAfterExactCompletedIntentCode() {
        var running = receiptPendingIntent().claim("claim", NOW.minusSeconds(1), Duration.ofMinutes(30));
        var completed = running.complete("claim", VerificationDispatchRunner.COMPLETED, NOW);
        assertEquals(VerificationActionEvidenceOwner.Status.CANONICAL_SUCCEEDED,
                receiptOwner(completed).assess(terminalRun()).status());
        var wrongCode = running.complete("claim", "UNTRUSTED_COMPLETION", NOW);
        assertEquals(VerificationActionEvidenceOwner.Status.INVALID_TERMINAL,
                receiptOwner(wrongCode).assess(terminalRun()).status());
        var orphaned = running.orphan("claim", VerificationDispatchRunner.ACTION_OWNER_INVALID, NOW);
        assertEquals(VerificationActionEvidenceOwner.Status.ORPHANED,
                receiptOwner(orphaned).assess(terminalRun()).status());
    }

    @Test
    void malformedReceiptExtensionOnCompletedIntentRemainsInvalidTerminal() {
        var terminal = terminalRun(); var store = new InMemoryRunStore(); var output = receiptOutput(terminal);
        var malformed = new HashMap<>(output); malformed.remove(VerificationReviewEvidenceOutput.HASH_KEY);
        store.create(actionRun(candidate(), terminal, "attempt-token", ActionRunStatus.SUCCEEDED,
                ActionExecutionResult.succeeded(malformed)));
        var completed = receiptPendingIntent().claim("claim", NOW.minusSeconds(1), Duration.ofMinutes(30))
                .complete("claim", VerificationDispatchRunner.COMPLETED, NOW);
        var owner = new ActionRuntimeVerificationEvidenceOwner(repository(completed), store, candidates(candidate()),
                (tenant, action, key) -> Optional.of("action-owner"));
        assertEquals(VerificationActionEvidenceOwner.Status.INVALID_TERMINAL, owner.assess(terminal).status());
    }

    private static VerificationDispatchIntent receiptPendingIntent() {
        return VerificationDispatchIntent.pending(
                VerificationDispatchOperationIds.derive(new VerificationRunInput(RUN, CANDIDATE, 0)),
                TENANT, RUN, CANDIDATE, 0, "action-owner", VerificationAttemptTokens.hash("attempt-token"),
                NOW.plusSeconds(600), NOW.minusSeconds(2));
    }

    private static ActionRuntimeVerificationEvidenceOwner receiptOwner(VerificationDispatchIntent intent) {
        var store = new InMemoryRunStore(); var terminal = terminalRun(); var candidate = candidate();
        store.create(actionRun(candidate, terminal, "attempt-token", ActionRunStatus.SUCCEEDED,
                ActionExecutionResult.succeeded(receiptOutput(terminal))));
        return new ActionRuntimeVerificationEvidenceOwner(repository(intent), store, candidates(candidate),
                (tenant, action, key) -> Optional.of("action-owner"));
    }

    private static Map<String, Object> receiptOutput(VerificationRun terminal) {
        var receiptHash = hash("7");
        var receipt = new io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock(
                new ArtifactReference(VerificationReviewEvidenceOutput.REFERENCE_PREFIX + receiptHash.sha256()), receiptHash);
        return VerificationReviewEvidenceOutput.add(output(terminal), Optional.of(receipt));
    }

    @Test
    void terminalDuplicateOwnerWithoutIntentStopsRequestedCachedFailureLoop() {
        CandidateVersion candidate = candidate();
        VerificationRun requested = requestedRun();
        InMemoryRunStore store = new InMemoryRunStore();
        store.create(actionRun(candidate, requested, "attempt-token", ActionRunStatus.FAILED,
                ActionExecutionResult.failed("ACTION_EXECUTION_EXCEPTION", "prepare failed")));
        var owner = new ActionRuntimeVerificationEvidenceOwner(
                repository(null), store, candidates(candidate), (tenant, action, key) -> Optional.of("action-owner"));

        assertEquals(
                VerificationActionEvidenceOwner.Status.INVALID_TERMINAL,
                owner.assess(requested).status());
    }

    @Test
    void canonicalResultRejectsExtraMissingAndNonBooleanExecutionEvidence() {
        VerificationRun terminal = terminalRun();
        Map<String, Object> extra = new HashMap<>(output(terminal));
        extra.put("untrusted", "value");
        Map<String, Object> missing = new HashMap<>(output(terminal));
        missing.remove("executedNow");
        Map<String, Object> wrongType = new HashMap<>(output(terminal));
        wrongType.put("executedNow", "true");

        assertFalse(VerificationDispatchRunner.exactTerminalResult(
                terminal, ActionExecutionResult.succeeded(extra)));
        assertFalse(VerificationDispatchRunner.exactTerminalResult(
                terminal, ActionExecutionResult.succeeded(missing)));
        assertFalse(VerificationDispatchRunner.exactTerminalResult(
                terminal, ActionExecutionResult.succeeded(wrongType)));
    }

    private static ActionRun actionRun(
            CandidateVersion candidate,
            VerificationRun run,
            String token,
            ActionRunStatus status,
            ActionExecutionResult result) {
        String operationId = VerificationDispatchOperationIds.derive(new VerificationRunInput(RUN, CANDIDATE, 0));
        return ActionRun.builder()
                .runId("action-owner")
                .version(status == ActionRunStatus.SUCCEEDED ? 5 : 4)
                .tenantId(TENANT.value())
                .userId("factory-verifier")
                .traceId("trace")
                .contextMetadata(Map.of(
                        "resource.type", VerificationRunAction.RESOURCE_TYPE,
                        "resource.id", CANDIDATE.value()))
                .actionId(VerificationRunAction.ACTION_ID)
                .proposalId("proposal")
                .requesterId("factory-verifier")
                .input(new VerificationRunInput(RUN, CANDIDATE, 0).toMap())
                .duplicateKey(VerificationRunIdempotencyKeys.derive(run, candidate, 0))
                .status(status)
                .currentStage("COMPLETE")
                .attemptToken(token)
                .externalOperationId(operationId)
                .result(result)
                .createdAt(NOW.minusSeconds(3))
                .updatedAt(NOW)
                .build();
    }

    private static Map<String, Object> output(VerificationRun run) {
        return Map.of(
                VerificationRunAction.VERIFICATION_RUN_ID, RUN.value(),
                "verificationStatus", run.status().name(),
                "terminalCode", run.terminalCode().orElseThrow(),
                "resultManifestRef", run.resultManifestRef().orElseThrow().value(),
                "executedNow", true);
    }

    private static VerificationDispatchIntentRepository repository(VerificationDispatchIntent value) {
        return new VerificationDispatchIntentRepository() {
            @Override public void create(VerificationDispatchIntent intent) { throw new UnsupportedOperationException(); }
            @Override public Optional<VerificationDispatchIntent> find(String id) { return Optional.ofNullable(value); }
            @Override public Optional<VerificationDispatchIntent> findLatest(TenantId tenant, VerificationRunId id) {
                return Optional.ofNullable(value);
            }
            @Override public Optional<VerificationDispatchIntent> claimNext(
                    Instant now, Duration lease, String token) { return Optional.empty(); }
            @Override public Optional<VerificationDispatchIntent> claimExpiredRunningForReconciliation(
                    Instant now, Duration lease, String token) { return Optional.empty(); }
            @Override public boolean compareAndSet(VerificationDispatchIntent expected, VerificationDispatchIntent next) {
                return false;
            }
        };
    }

    private static CandidateVersionRepository candidates(CandidateVersion value) {
        return new CandidateVersionRepository() {
            @Override public void create(CandidateVersion candidate) { throw new UnsupportedOperationException(); }
            @Override public Optional<CandidateVersion> find(TenantId tenant, CandidateId id) {
                return tenant.equals(TENANT) && id.equals(CANDIDATE) ? Optional.of(value) : Optional.empty();
            }
            @Override public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                    TenantId tenant, BuildSessionId session, WorkOrderId workOrder) { return Optional.empty(); }
        };
    }

    private static VerificationRun requestedRun() {
        return new VerificationRun(
                RUN, TENANT, new BuildSessionId("session-owner"), CANDIDATE, hash("1"),
                ActionBackedVerificationRunLauncher.GATE_PROFILE, hash("3"), hash("4"),
                VerificationRunStatus.REQUESTED, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), 0,
                NOW.minusSeconds(3), NOW.minusSeconds(3));
    }

    private static VerificationRun terminalRun() {
        return requestedRun().start(NOW.minusSeconds(2)).complete(
                VerificationRunStatus.PASSED,
                new ArtifactReference("artifact:result"),
                hash("5"),
                "VERIFIED",
                VerificationDisposition.REVIEW_ELIGIBLE,
                NOW.minusSeconds(1));
    }

    private static CandidateVersion candidate() {
        return new CandidateVersion(
                CANDIDATE, TENANT, new BuildSessionId("session-owner"), Optional.empty(),
                new ArtifactReference("artifact:source"), hash("1"),
                new ArtifactReference("artifact:dependency"), hash("2"),
                new ArtifactReference("artifact:toolchain"), hash("3"),
                CandidateVersionStatus.GENERATED, new WorkOrderId("work-owner"), NOW.minusSeconds(4));
    }

    private static ContentHash hash(String digit) {
        return new ContentHash(digit.repeat(64));
    }
}
