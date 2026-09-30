package io.github.flowerjvm.factory.application.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.action.VerificationRunAction;
import io.github.flowerjvm.factory.application.action.VerificationRunIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.VerificationRunInput;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.verification.VerificationResult;
import io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes;
import io.github.flowerjvm.factory.contracts.verification.VerificationStatus;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.RetryDisposition;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Runner-boundary tests; synthetic records do not claim product or native database verification. */
class VerificationDispatchRunnerTest {
    private static final TenantId TENANT = new TenantId("tenant-review-receipt");
    private static final BuildSessionId SESSION = new BuildSessionId("session-review-receipt");
    private static final CandidateId CANDIDATE = new CandidateId("candidate-review-receipt");
    private static final VerificationRunId RUN = new VerificationRunId("verification-review-receipt");
    private static final String ACTION_RUN = "action-review-receipt";
    private static final String ATTEMPT_TOKEN = "synthetic-review-attempt";
    private static final Instant NOW = Instant.parse("2026-09-06T04:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final ArtifactReference SOURCE = new ArtifactReference("artifact:receipt-test-source");
    private static final ArtifactReference RESULT = new ArtifactReference("artifact:receipt-test-result");
    private static final CertificationArtifactLock RECEIPT = new CertificationArtifactLock(
            new ArtifactReference(VerificationReviewEvidenceOutput.REFERENCE_PREFIX + "a".repeat(64)), hash("a"));

    @Test
    void passedVerificationRecordsExactReceiptBeforeCanonicalActionCompletion() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        var recorded = new AtomicInteger();
        VerificationReviewEvidenceRecorder recorder = (intent, action, domain) -> {
            assertEquals(fixture.intent(), intent);
            assertEquals(VerificationDispatchIntentStatus.RUNNING, intent.status());
            assertEquals(fixture.action(), action);
            assertEquals(ActionRunStatus.WAITING_EXTERNAL, action.status());
            assertEquals(VerificationRunStatus.PASSED, domain.status());
            assertEquals(2, domain.version());
            assertEquals(1, fixture.verifierCalls.get());
            assertEquals(VerificationActionEvidenceOwner.Status.PENDING, fixture.ownerStatus());
            assertEquals(0, fixture.runtime.completions.get());
            recorded.incrementAndGet();
            return Optional.of(RECEIPT);
        };

        // Exercise the default lease overload as well as the explicit lease recovery tests below.
        var runner = new VerificationDispatchRunner(fixture.intents, fixture.runs, fixture.candidates,
                fixture.execution, fixture.runtime, fixture.actions, fixture.clock, recorder);
        assertTrue(runner.tickOnce());

        assertEquals(1, recorded.get());
        assertEquals(1, fixture.runtime.completions.get());
        assertEquals(withReceipt(fixture.domain(), true), fixture.action().result().output());
        assertCanonical(fixture);
        assertFalse(runner.tickOnce());
        assertEquals(1, recorded.get());
        assertEquals(1, fixture.verifierCalls.get());
    }

    @Test
    void failedVerificationKeepsLegacyFiveFieldOutputWhenRecorderReturnsNoReceipt() {
        var fixture = new Fixture(VerificationStatus.FAILED);
        var calls = new AtomicInteger();
        assertTrue(fixture.runner((intent, action, run) -> {
            assertEquals(VerificationRunStatus.FAILED, run.status());
            calls.incrementAndGet();
            return Optional.empty();
        }).tickOnce());

        assertEquals(1, calls.get());
        assertEquals(ActionRunStatus.SUCCEEDED, fixture.action().status());
        assertEquals(baseOutput(fixture.domain(), true), fixture.action().result().output());
        assertEquals(Optional.empty(), VerificationReviewEvidenceOutput.lock(fixture.action().result().output()));
        assertEquals(VerificationDisposition.REPAIR_REQUIRED, fixture.domain().disposition().orElseThrow());
        assertEquals(VerificationDispatchRunner.COMPLETED, fixture.intent().lastCode().orElseThrow());
    }

    @Test
    void legacyPassedConstructorStillProducesExactlyTheOriginalFiveFields() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        assertTrue(new VerificationDispatchRunner(fixture.intents, fixture.runs, fixture.candidates,
                fixture.execution, fixture.runtime, fixture.actions, fixture.clock).tickOnce());
        assertEquals(baseOutput(fixture.domain(), true), fixture.action().result().output());
        assertCanonical(fixture);
    }

    @Test
    void recorderFailureAfterVerifierPassFailsActionAndCannotBecomeCanonical() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        assertTrue(fixture.runner((intent, action, run) -> {
            throw new IllegalStateException("synthetic exact evidence mismatch");
        }).tickOnce());

        assertEquals(VerificationRunStatus.PASSED, fixture.domain().status());
        assertEquals(1, fixture.verifierCalls.get());
        assertReviewBlocked(fixture);
        assertFalse(fixture.runner(VerificationReviewEvidenceRecorder.NONE).tickOnce());
        assertEquals(1, fixture.verifierCalls.get());
    }

    @Test
    void failedVerificationCannotAcquireReceiptEvenFromMisbehavingRecorder() {
        var fixture = new Fixture(VerificationStatus.FAILED);
        assertTrue(fixture.runner((intent, action, run) -> Optional.of(RECEIPT)).tickOnce());
        assertEquals(VerificationRunStatus.FAILED, fixture.domain().status());
        assertReviewBlocked(fixture);
    }

    @Test
    void malformedRecorderLockCannotCompleteCanonicalAction() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        var wrongPrefix = new CertificationArtifactLock(new ArtifactReference("artifact:unrelated"), hash("a"));
        assertTrue(fixture.runner((intent, action, run) -> Optional.of(wrongPrefix)).tickOnce());
        assertReviewBlocked(fixture);
    }

    @Test
    void crashAfterReadbackBeforeActionCompletionReplaysOnlyReadbackAfterLeaseExpiry() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        var readbacks = new AtomicInteger();
        var immutableReceipts = new HashSet<CertificationArtifactLock>();
        VerificationReviewEvidenceRecorder recorder = (intent, action, run) -> {
            readbacks.incrementAndGet();
            immutableReceipts.add(RECEIPT);
            return Optional.of(RECEIPT);
        };
        fixture.runtime.beforeCompletion = () -> { throw new SimulatedProcessDeath(); };
        assertThrows(SimulatedProcessDeath.class, () -> fixture.runner(recorder).tickOnce());
        assertEquals(VerificationRunStatus.PASSED, fixture.domain().status());
        assertEquals(ActionRunStatus.WAITING_EXTERNAL, fixture.action().status());
        assertEquals(VerificationDispatchIntentStatus.RUNNING, fixture.intent().status());
        assertEquals(VerificationActionEvidenceOwner.Status.PENDING, fixture.ownerStatus());
        assertEquals(1, fixture.verifierCalls.get());
        assertEquals(1, readbacks.get());

        fixture.runtime.beforeCompletion = () -> {};
        fixture.clock.now = NOW.plus(LEASE).minusNanos(1);
        assertFalse(fixture.runner(recorder).tickOnce());
        fixture.clock.now = NOW.plus(LEASE);
        assertTrue(fixture.runner(recorder).tickOnce());

        assertEquals(1, fixture.verifierCalls.get(), "terminal domain state never licenses verifier replay");
        assertEquals(2, readbacks.get());
        assertEquals(Set.of(RECEIPT), immutableReceipts);
        assertEquals(withReceipt(fixture.domain(), false), fixture.action().result().output());
        assertEquals(2, fixture.intent().attemptCount());
        assertCanonical(fixture);
    }

    @Test
    void completedActionWithPendingIntentCheckpointRecoversWithoutRewritingReceipt() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        var readbacks = new AtomicInteger();
        fixture.intents.beforeTerminalCas = () -> { throw new SimulatedProcessDeath(); };
        assertThrows(SimulatedProcessDeath.class, () -> fixture.runner((intent, action, run) -> {
            readbacks.incrementAndGet();
            return Optional.of(RECEIPT);
        }).tickOnce());

        ActionRun committedAction = fixture.action();
        assertEquals(ActionRunStatus.SUCCEEDED, committedAction.status());
        assertEquals(VerificationDispatchIntentStatus.RUNNING, fixture.intent().status());
        // Receipt eligibility waits for both durable owners, including the intent completion
        // checkpoint. The intervening gap is pending, not canonical or corrupt evidence.
        assertEquals(VerificationActionEvidenceOwner.Status.PENDING, fixture.ownerStatus());
        fixture.intents.beforeTerminalCas = () -> {};
        fixture.clock.now = NOW.plus(LEASE);
        assertTrue(fixture.runner((intent, action, run) -> {
            throw new AssertionError("canonical Action receipt must not be rewritten during reconciliation");
        }).tickOnce());

        assertEquals(committedAction, fixture.action());
        assertEquals(1, readbacks.get());
        assertEquals(1, fixture.runtime.completions.get());
        assertEquals(1, fixture.verifierCalls.get());
        assertCanonical(fixture);
    }

    @Test
    void alreadyTerminalDomainWithPendingIntentUsesRecorderWithoutVerifierReplay() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        fixture.execution.execute(TENANT, RUN, 0);
        assertEquals(VerificationDispatchIntentStatus.PENDING, fixture.intent().status());
        assertTrue(fixture.runner((intent, action, run) -> Optional.of(RECEIPT)).tickOnce());
        assertEquals(1, fixture.verifierCalls.get());
        assertEquals(withReceipt(fixture.domain(), false), fixture.action().result().output());
        assertCanonical(fixture);
    }

    @Test
    void expiredClaimReconciliationAlsoFailsClosedOnRecorderFailure() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        fixture.execution.execute(TENANT, RUN, 0);
        fixture.intents.claimNext(NOW, LEASE, "synthetic-crashed-claim").orElseThrow();
        fixture.clock.now = NOW.plus(LEASE);
        assertTrue(fixture.runner((intent, action, run) -> {
            throw new IllegalArgumentException("synthetic recovery evidence drift");
        }).tickOnce());
        assertEquals(1, fixture.verifierCalls.get());
        assertReviewBlocked(fixture);
    }

    @Test
    void receiptReadbackReachingExactClaimExpiryCannotCompleteButRecoveryReadsOnlyOnceMore() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        var readbacks = new AtomicInteger();
        assertTrue(fixture.runner((intent, action, run) -> {
            readbacks.incrementAndGet();
            fixture.clock.now = intent.leaseUntil().orElseThrow();
            return Optional.of(RECEIPT);
        }).tickOnce());

        assertEquals(VerificationRunStatus.PASSED, fixture.domain().status());
        assertEquals(ActionRunStatus.WAITING_EXTERNAL, fixture.action().status());
        assertEquals(VerificationDispatchIntentStatus.RUNNING, fixture.intent().status());
        assertEquals(VerificationActionEvidenceOwner.Status.PENDING, fixture.ownerStatus());
        assertEquals(0, fixture.runtime.completions.get());
        assertTrue(fixture.runner((intent, action, run) -> {
            readbacks.incrementAndGet();
            return Optional.of(RECEIPT);
        }).tickOnce());

        assertEquals(2, readbacks.get());
        assertEquals(1, fixture.verifierCalls.get());
        assertEquals(withReceipt(fixture.domain(), false), fixture.action().result().output());
        assertCanonical(fixture);
    }

    @Test
    void transferredClaimDuringReadbackFencesStaleSuccessAndPreservesNewOwnerForRecovery() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        assertTrue(fixture.runner((intent, action, run) -> {
            fixture.clock.now = intent.leaseUntil().orElseThrow();
            fixture.intents.claimExpiredRunningForReconciliation(fixture.clock.now, LEASE,
                    "synthetic-replacement-owner").orElseThrow();
            return Optional.of(RECEIPT);
        }).tickOnce());

        assertEquals("synthetic-replacement-owner", fixture.intent().claimToken().orElseThrow());
        assertEquals(ActionRunStatus.WAITING_EXTERNAL, fixture.action().status());
        assertEquals(VerificationActionEvidenceOwner.Status.PENDING, fixture.ownerStatus());
        assertEquals(0, fixture.runtime.completions.get());
        assertFalse(fixture.runner(VerificationReviewEvidenceRecorder.NONE).tickOnce());
        fixture.clock.now = fixture.intent().leaseUntil().orElseThrow();
        assertTrue(fixture.runner((intent, action, run) -> Optional.of(RECEIPT)).tickOnce());

        assertEquals(3, fixture.intent().attemptCount());
        assertEquals(1, fixture.verifierCalls.get());
        assertEquals(withReceipt(fixture.domain(), false), fixture.action().result().output());
        assertCanonical(fixture);
    }

    @Test
    void failedReadbackAfterClaimTransferCannotFailAnotherOwnersActionAttempt() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        assertTrue(fixture.runner((intent, action, run) -> {
            fixture.clock.now = intent.leaseUntil().orElseThrow();
            fixture.intents.claimExpiredRunningForReconciliation(fixture.clock.now, LEASE,
                    "synthetic-replacement-owner").orElseThrow();
            throw new IllegalStateException("stale owner's synthetic evidence failure");
        }).tickOnce());

        assertEquals(ActionRunStatus.WAITING_EXTERNAL, fixture.action().status());
        assertEquals("synthetic-replacement-owner", fixture.intent().claimToken().orElseThrow());
        assertEquals(0, fixture.runtime.completions.get());
        fixture.clock.now = fixture.intent().leaseUntil().orElseThrow();
        assertTrue(fixture.runner((intent, action, run) -> Optional.of(RECEIPT)).tickOnce());
        assertEquals(1, fixture.verifierCalls.get());
        assertCanonical(fixture);
    }

    @Test
    void exactDeadlineBeforeDispatchInvokesNeitherVerifierNorRecorder() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        fixture.clock.now = fixture.intent().deadlineAt();
        assertTrue(fixture.runner((intent, action, run) -> {
            throw new AssertionError("expired unstarted verification cannot read review evidence");
        }).tickOnce());
        assertEquals(0, fixture.verifierCalls.get());
        assertEquals(VerificationRunStatus.REQUESTED, fixture.domain().status());
        assertEquals(ActionRunStatus.FAILED, fixture.action().status());
        assertEquals(VerificationDispatchRunner.DEADLINE_EXCEEDED, fixture.action().result().code());
        assertEquals(VerificationActionEvidenceOwner.Status.INVALID_TERMINAL, fixture.ownerStatus());
    }

    @Test
    void deadlineReachedDuringReadbackCannotTurnRecorderRejectionIntoReviewEligibility() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        // Keep the ownership lease live beyond this deadline so this case isolates the recorder
        // rejection; separate tests cover an expired or transferred claim returning no completion.
        assertTrue(new VerificationDispatchRunner(fixture.intents, fixture.runs, fixture.candidates,
                fixture.execution, fixture.runtime, fixture.actions, fixture.clock, (intent, action, run) -> {
            // The production recorder owns the current session/deadline read gate. This fixture
            // tests that its post-read rejection cannot be swallowed by terminal-domain recovery.
            fixture.clock.now = intent.deadlineAt();
            throw new IllegalStateException("synthetic session deadline reached during evidence readback");
        }).tickOnce());
        assertReviewBlocked(fixture);
        assertEquals(1, fixture.verifierCalls.get());
        fixture.clock.now = fixture.clock.now.plus(LEASE);
        assertFalse(fixture.runner((intent, action, run) -> Optional.of(RECEIPT)).tickOnce());
        assertReviewBlocked(fixture);
    }

    @Test
    void cancellationWinningBetweenReceiptAndCompletionPreservesFirstTerminalAndNoEligibility() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        var receipts = new AtomicInteger();
        fixture.runtime.beforeCompletion = () -> fixture.runtime.cancel(ACTION_RUN, "synthetic cancel race");
        assertTrue(fixture.runner((intent, action, run) -> {
            receipts.incrementAndGet();
            return Optional.of(RECEIPT);
        }).tickOnce());

        ActionRun cancelled = fixture.action();
        assertEquals(ActionRunStatus.CANCELLED, cancelled.status());
        assertFalse(cancelled.result().output().containsKey(VerificationReviewEvidenceOutput.REFERENCE_KEY));
        assertEquals(VerificationDispatchIntentStatus.RUNNING, fixture.intent().status());
        assertEquals(VerificationActionEvidenceOwner.Status.PENDING, fixture.ownerStatus());
        fixture.runtime.beforeCompletion = () -> {};
        fixture.clock.now = NOW.plus(LEASE);
        assertTrue(fixture.runner((intent, action, run) -> {
            throw new AssertionError("cancelled Action must not read back again");
        }).tickOnce());

        assertEquals(cancelled, fixture.action());
        assertEquals(1, receipts.get());
        assertEquals(0, fixture.runtime.completions.get());
        assertEquals(1, fixture.verifierCalls.get());
        assertEquals(VerificationDispatchIntentStatus.COMPLETED, fixture.intent().status());
        assertEquals(VerificationActionEvidenceOwner.Status.INVALID_TERMINAL, fixture.ownerStatus());
    }

    @Test
    void cancellationBeforeFirstClaimNeverCallsVerifierOrRecorder() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        fixture.runtime.cancel(ACTION_RUN, "synthetic cancellation");
        assertTrue(fixture.runner((intent, action, run) -> {
            throw new AssertionError("terminal cancellation must not record review receipt");
        }).tickOnce());
        assertEquals(0, fixture.verifierCalls.get());
        assertEquals(ActionRunStatus.CANCELLED, fixture.action().status());
        assertEquals(VerificationActionEvidenceOwner.Status.INVALID_TERMINAL, fixture.ownerStatus());
    }

    @Test
    void terminalResultAcceptsOnlyLegacyBaseOrCompleteExactReceiptForPassedRun() {
        VerificationRun passed = terminal(VerificationRunStatus.PASSED);
        assertTrue(exact(passed, baseOutput(passed, true)));
        assertTrue(exact(passed, withReceipt(passed, false)));
        VerificationRun failed = terminal(VerificationRunStatus.FAILED);
        assertTrue(exact(failed, baseOutput(failed, true)));
        assertFalse(exact(failed, withReceipt(failed, true)));
    }

    @Test
    void terminalResultRejectsEveryPartialReceiptIncludingOnlyOneExtensionKey() {
        VerificationRun run = terminal(VerificationRunStatus.PASSED);
        for (String key : VerificationReviewEvidenceOutput.KEYS) {
            var missingOne = new HashMap<>(withReceipt(run, true));
            missingOne.remove(key);
            assertFalse(exact(run, missingOne), "missing " + key);
            var onlyOne = new HashMap<>(baseOutput(run, true));
            onlyOne.put(key, withReceipt(run, true).get(key));
            assertFalse(exact(run, onlyOne), "only " + key);
        }
    }

    @Test
    void terminalResultRejectsReceiptSchemaTypePrefixHashMismatchAndNoncanonicalHash() {
        VerificationRun run = terminal(VerificationRunStatus.PASSED);
        Map<String, List<Object>> mutations = Map.of(
                VerificationReviewEvidenceOutput.SCHEMA_KEY,
                List.of("factory.verification-review-evidence.v2", 1, true),
                VerificationReviewEvidenceOutput.REFERENCE_KEY,
                List.of("artifact:other:" + hash("a").sha256(),
                        VerificationReviewEvidenceOutput.REFERENCE_PREFIX + hash("b").sha256(), 1),
                VerificationReviewEvidenceOutput.HASH_KEY,
                List.of("A".repeat(64), "a".repeat(63), "g".repeat(64), hash("b").sha256(), 1));
        mutations.forEach((key, values) -> values.forEach(value -> {
            var output = new HashMap<>(withReceipt(run, true));
            output.put(key, value);
            assertFalse(exact(run, output), key + "=" + value);
        }));
    }

    @Test
    void validReceiptDoesNotPermitUnknownOrMissingBaseFieldsOrForgedDomainOutput() {
        VerificationRun run = terminal(VerificationRunStatus.PASSED);
        var extra = new HashMap<>(withReceipt(run, true));
        extra.put("reviewEligible", true);
        assertFalse(exact(run, extra));
        for (String key : baseOutput(run, true).keySet()) {
            var missing = new HashMap<>(withReceipt(run, true));
            missing.remove(key);
            assertFalse(exact(run, missing), "missing " + key);
            var forged = new HashMap<>(withReceipt(run, true));
            forged.put(key, "synthetic wrong value");
            assertFalse(exact(run, forged), "forged " + key);
        }
    }

    @Test
    void malformedExistingTerminalReceiptIsNotCanonicalAndIsNeverRewritten() {
        var fixture = new Fixture(VerificationStatus.PASSED);
        fixture.execution.execute(TENANT, RUN, 0);
        var malformed = new HashMap<>(withReceipt(fixture.domain(), true));
        malformed.remove(VerificationReviewEvidenceOutput.HASH_KEY);
        ActionRun parked = fixture.action();
        ActionRun badTerminal = parked.toBuilder().version(parked.version() + 1)
                .status(ActionRunStatus.SUCCEEDED).currentStage("COMPLETE")
                .result(ActionExecutionResult.succeeded(malformed)).build();
        assertTrue(fixture.actions.compareAndSet(parked, badTerminal));
        assertTrue(fixture.runner((intent, action, run) -> {
            throw new AssertionError("terminal Action output must not be retroactively repaired");
        }).tickOnce());
        assertEquals(badTerminal, fixture.action());
        assertEquals(VerificationActionEvidenceOwner.Status.INVALID_TERMINAL, fixture.ownerStatus());
        assertEquals(1, fixture.verifierCalls.get());
        assertEquals(0, fixture.runtime.completions.get());
        assertFalse(fixture.intent().lastCode().filter(VerificationDispatchRunner.COMPLETED::equals).isPresent());
    }

    private static void assertCanonical(Fixture fixture) {
        assertEquals(ActionRunStatus.SUCCEEDED, fixture.action().status());
        assertEquals(VerificationDispatchIntentStatus.COMPLETED, fixture.intent().status());
        assertEquals(VerificationDispatchRunner.COMPLETED, fixture.intent().lastCode().orElseThrow());
        assertEquals(VerificationActionEvidenceOwner.Status.CANONICAL_SUCCEEDED, fixture.ownerStatus());
        assertTrue(VerificationDispatchRunner.exactTerminalResult(fixture.domain(), fixture.action().result()));
    }

    private static void assertReviewBlocked(Fixture fixture) {
        assertEquals(ActionRunStatus.FAILED, fixture.action().status());
        assertEquals(VerificationDispatchRunner.REVIEW_EVIDENCE_BLOCKED, fixture.action().result().code());
        assertEquals(RetryDisposition.MANUAL_REVIEW, fixture.action().result().retryDisposition());
        assertEquals(Map.of(VerificationRunAction.VERIFICATION_RUN_ID, RUN.value()),
                fixture.action().result().output());
        assertEquals(VerificationDispatchIntentStatus.COMPLETED, fixture.intent().status());
        assertEquals(VerificationActionEvidenceOwner.Status.INVALID_TERMINAL, fixture.ownerStatus());
    }

    private static boolean exact(VerificationRun run, Map<String, Object> output) {
        return VerificationDispatchRunner.exactTerminalResult(run, ActionExecutionResult.succeeded(output));
    }

    private static Map<String, Object> baseOutput(VerificationRun run, boolean executedNow) {
        return Map.of(VerificationRunAction.VERIFICATION_RUN_ID, run.verificationRunId().value(),
                "verificationStatus", run.status().name(), "terminalCode", run.terminalCode().orElseThrow(),
                "resultManifestRef", run.resultManifestRef().orElseThrow().value(), "executedNow", executedNow);
    }

    private static Map<String, Object> withReceipt(VerificationRun run, boolean executedNow) {
        var output = new HashMap<>(baseOutput(run, executedNow));
        output.put("reviewEvidenceSchemaVersion", "factory.verification-review-evidence.v1");
        output.put("reviewEvidenceRef", RECEIPT.reference().value());
        output.put("reviewEvidenceHash", RECEIPT.hash().sha256());
        return Map.copyOf(output);
    }

    private static VerificationRun terminal(VerificationRunStatus status) {
        return requested().start(NOW).complete(status, RESULT, hash("5"),
                status == VerificationRunStatus.PASSED ? VerificationStableCodes.VERIFIED : "SYNTHETIC_REPAIR_REQUIRED",
                status == VerificationRunStatus.PASSED
                        ? VerificationDisposition.REVIEW_ELIGIBLE : VerificationDisposition.REPAIR_REQUIRED, NOW);
    }

    private static VerificationRun requested() {
        return new VerificationRun(RUN, TENANT, SESSION, CANDIDATE, hash("1"),
                ActionBackedVerificationRunLauncher.GATE_PROFILE, hash("3"), hash("4"),
                VerificationRunStatus.REQUESTED, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW.minusSeconds(1), NOW.minusSeconds(1));
    }

    private static CandidateVersion candidate() {
        return new CandidateVersion(CANDIDATE, TENANT, SESSION, Optional.empty(), SOURCE, hash("1"),
                new ArtifactReference("artifact:receipt-test-dependency"), hash("2"),
                new ArtifactReference("artifact:receipt-test-toolchain"), hash("3"),
                CandidateVersionStatus.GENERATED, new WorkOrderId("work-order-receipt-test"), NOW.minusSeconds(2));
    }

    private static ContentHash hash(String digit) { return new ContentHash(digit.repeat(64)); }

    private static final class Fixture {
        final MutableClock clock = new MutableClock();
        final MemoryIntents intents = new MemoryIntents();
        final MemoryRuns runs = new MemoryRuns();
        final InMemoryRunStore actions = new InMemoryRunStore();
        final TestCompletableRuntime runtime = new TestCompletableRuntime(actions, clock);
        final AtomicInteger verifierCalls = new AtomicInteger();
        final CandidateVersionRepository candidates = new CandidateVersionRepository() {
            @Override public void create(CandidateVersion candidate) { throw new UnsupportedOperationException(); }
            @Override public Optional<CandidateVersion> find(TenantId tenant, CandidateId id) {
                return TENANT.equals(tenant) && CANDIDATE.equals(id) ? Optional.of(candidate()) : Optional.empty();
            }
            @Override public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                    TenantId tenant, BuildSessionId session, WorkOrderId workOrder) { return Optional.empty(); }
        };
        final VerificationExecutionService execution;

        Fixture(VerificationStatus status) {
            var artifacts = new MemoryArtifacts();
            artifacts.store(new Artifact(TENANT, SOURCE, hash("9"), "application/json", bytes("{}")));
            artifacts.store(new Artifact(TENANT, RESULT, hash("5"), "application/json", bytes("{}")));
            artifacts.store(new Artifact(TENANT, candidate().dependencyLockRef(), hash("2"),
                    "application/json", bytes("synthetic dependency")));
            execution = new VerificationExecutionService(runs, candidates, artifacts, request -> {
                verifierCalls.incrementAndGet();
                assertEquals(RUN, request.verificationRunId());
                assertEquals(TENANT, request.tenantId());
                assertEquals(SESSION, request.buildSessionId());
                assertEquals(CANDIDATE, request.candidateId());
                assertEquals(SOURCE, request.candidateManifest());
                return new VerificationResult(status,
                        status == VerificationStatus.PASSED
                                ? VerificationDisposition.REVIEW_ELIGIBLE : VerificationDisposition.REPAIR_REQUIRED,
                        List.of(status == VerificationStatus.PASSED
                                ? VerificationStableCodes.VERIFIED : "SYNTHETIC_REPAIR_REQUIRED"),
                        RESULT, hash("5"), List.of(RESULT));
            }, clock, new ArtifactReference("artifact:receipt-test-fixtures"));
            var input = new VerificationRunInput(RUN, CANDIDATE, 0);
            intents.create(VerificationDispatchIntent.pending(VerificationDispatchOperationIds.derive(input),
                    TENANT, RUN, CANDIDATE, 0, ACTION_RUN, VerificationAttemptTokens.hash(ATTEMPT_TOKEN),
                    NOW.plusSeconds(600), NOW.minusSeconds(1)));
            actions.create(ActionRun.builder().runId(ACTION_RUN).version(4).tenantId(TENANT.value())
                    .userId("factory-verifier").traceId("trace-review-receipt")
                    .contextMetadata(Map.of("resource.type", VerificationRunAction.RESOURCE_TYPE,
                            "resource.id", CANDIDATE.value()))
                    .actionId(VerificationRunAction.ACTION_ID).proposalId("proposal-review-receipt")
                    .requesterId("factory-verifier").input(input.toMap())
                    .duplicateKey(VerificationRunIdempotencyKeys.derive(requested(), candidate(), 0))
                    .status(ActionRunStatus.WAITING_EXTERNAL).currentStage("EXECUTE")
                    .attemptToken(ATTEMPT_TOKEN).externalOperationId(intents.value.operationId())
                    .createdAt(NOW.minusSeconds(2)).updatedAt(NOW.minusSeconds(1)).build());
        }

        VerificationDispatchRunner runner(VerificationReviewEvidenceRecorder recorder) {
            return new VerificationDispatchRunner(intents, runs, candidates, execution, runtime, actions,
                    clock, LEASE, Duration.ofSeconds(30), recorder);
        }

        VerificationRun domain() { return runs.find(TENANT, RUN).orElseThrow(); }
        ActionRun action() { return actions.find(ACTION_RUN).orElseThrow(); }
        VerificationDispatchIntent intent() { return intents.findLatest(TENANT, RUN).orElseThrow(); }
        VerificationActionEvidenceOwner.Status ownerStatus() {
            return new ActionRuntimeVerificationEvidenceOwner(intents, actions, candidates,
                    (tenant, action, key) -> Optional.empty()).assess(domain()).status();
        }
        private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    }

    private static final class MemoryRuns implements VerificationRunRepository {
        private VerificationRun value = requested();
        @Override public void create(VerificationRun run) { throw new UnsupportedOperationException(); }
        @Override public Optional<VerificationRun> find(TenantId tenant, VerificationRunId id) {
            return value.tenantId().equals(tenant) && value.verificationRunId().equals(id)
                    ? Optional.of(value) : Optional.empty();
        }
        @Override public Optional<VerificationRun> findLatestForCandidate(TenantId tenant, BuildSessionId session,
                CandidateId candidate, ContentHash hash, String profile) {
            return value.tenantId().equals(tenant) && value.buildSessionId().equals(session)
                            && value.candidateId().equals(candidate) && value.candidateHash().equals(hash)
                            && value.gateProfile().equals(profile) ? Optional.of(value) : Optional.empty();
        }
        @Override public synchronized boolean compareAndSet(VerificationRun expected, VerificationRun next) {
            if (!expected.equals(value) || next.version() != expected.version() + 1) return false;
            value = next;
            return true;
        }
    }

    private static final class MemoryArtifacts implements ArtifactStore {
        private final Map<ArtifactReference, Artifact> values = new HashMap<>();
        @Override public ArtifactReference store(Artifact artifact) {
            values.put(artifact.reference(), artifact);
            return artifact.reference();
        }
        @Override public Optional<Artifact> find(TenantId tenant, ArtifactReference reference) {
            return Optional.ofNullable(values.get(reference)).filter(value -> value.tenantId().equals(tenant));
        }
    }

    private static final class MemoryIntents implements VerificationDispatchIntentRepository {
        private VerificationDispatchIntent value;
        private Runnable beforeTerminalCas = () -> {};
        @Override public synchronized void create(VerificationDispatchIntent intent) {
            if (value != null) throw new IllegalStateException("duplicate intent");
            value = intent;
        }
        @Override public synchronized Optional<VerificationDispatchIntent> find(String id) {
            return value != null && value.operationId().equals(id) ? Optional.of(value) : Optional.empty();
        }
        @Override public synchronized Optional<VerificationDispatchIntent> findLatest(TenantId tenant, VerificationRunId id) {
            return value != null && value.tenantId().equals(tenant) && value.verificationRunId().equals(id)
                    ? Optional.of(value) : Optional.empty();
        }
        @Override public synchronized Optional<VerificationDispatchIntent> claimNext(Instant now, Duration lease, String token) {
            if (value == null || (value.status() != VerificationDispatchIntentStatus.PENDING
                    && !(value.status() == VerificationDispatchIntentStatus.UNCERTAIN
                        && value.leaseUntil().filter(expiry -> !now.isBefore(expiry)).isPresent()))) {
                return Optional.empty();
            }
            value = value.claim(token, now, lease);
            return Optional.of(value);
        }
        @Override public synchronized Optional<VerificationDispatchIntent> claimExpiredRunningForReconciliation(
                Instant now, Duration lease, String token) {
            if (value == null || value.status() != VerificationDispatchIntentStatus.RUNNING
                    || value.leaseUntil().filter(expiry -> !now.isBefore(expiry)).isEmpty()) return Optional.empty();
            value = value.claim(token, now, lease);
            return Optional.of(value);
        }
        @Override public synchronized boolean compareAndSet(VerificationDispatchIntent expected, VerificationDispatchIntent next) {
            if (!expected.equals(value) || next.version() != expected.version() + 1) return false;
            if (next.status() == VerificationDispatchIntentStatus.COMPLETED
                    || next.status() == VerificationDispatchIntentStatus.ORPHANED) beforeTerminalCas.run();
            value = next;
            return true;
        }
    }

    /** CAS completion fixture, not a substitute for the existing full registered-action contract tests. */
    private static final class TestCompletableRuntime implements CompletableActionRuntime {
        private final InMemoryRunStore store;
        private final Clock clock;
        private final AtomicInteger completions = new AtomicInteger();
        private Runnable beforeCompletion = () -> {};
        private TestCompletableRuntime(InMemoryRunStore store, Clock clock) {
            this.store = store;
            this.clock = clock;
        }
        @Override public ActionExecutionResult complete(String id, String attempt, ActionExecutionResult result) {
            beforeCompletion.run();
            ActionRun current = store.find(id).orElseThrow();
            if (!attempt.equals(current.attemptToken())) throw new IllegalStateException("wrong completion attempt");
            if (current.status().isTerminal()) return current.result();
            if (current.status() != ActionRunStatus.WAITING_EXTERNAL) throw new IllegalStateException("not parked");
            ActionRun next = current.toBuilder().version(current.version() + 1)
                    .status(result.status() == ActionExecutionStatus.SUCCEEDED ? ActionRunStatus.SUCCEEDED : ActionRunStatus.FAILED)
                    .currentStage("COMPLETE").result(result).updatedAt(clock.instant()).build();
            if (!store.compareAndSet(current, next)) throw new IllegalStateException("lost terminal CAS");
            completions.incrementAndGet();
            return result;
        }
        @Override public ActionExecutionResult cancel(String id, String reason) {
            ActionRun current = store.find(id).orElseThrow();
            if (current.status().isTerminal()) return current.result();
            var result = new ActionExecutionResult(ActionExecutionStatus.FAILED, "SYNTHETIC_CANCELLED", reason,
                    Map.of(), RetryDisposition.MANUAL_REVIEW);
            assertTrue(store.compareAndSet(current, current.toBuilder().version(current.version() + 1)
                    .status(ActionRunStatus.CANCELLED).currentStage("CANCEL").result(result)
                    .updatedAt(clock.instant()).build()));
            return result;
        }
        @Override public ActionExecutionResult resume(String id, ApprovalDecision decision) { throw new UnsupportedOperationException(); }
        @Override public ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context) { throw new UnsupportedOperationException(); }
    }

    private static final class MutableClock extends Clock {
        private Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Deliberately bypasses RuntimeException recovery to model loss of the process, not a retryable error. */
    private static final class SimulatedProcessDeath extends Error {}
}
