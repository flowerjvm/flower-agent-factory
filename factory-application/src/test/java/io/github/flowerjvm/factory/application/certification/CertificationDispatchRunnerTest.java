package io.github.flowerjvm.factory.application.certification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.action.CertificationIssueAction;
import io.github.flowerjvm.factory.application.action.CertificationIssueIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.CertificationIssueInput;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationEvidenceValidator;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.AgentPackCompatibilityDescriptor;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationEvidenceManifest;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentManifest;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CertificationDispatchRunnerTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void fullEvidenceIsCheckedBeforeCertificationCommitAndBeforeActionSuccess() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL);
        AtomicInteger reads = new AtomicInteger();
        fixture.withFullEvidence(run -> { reads.incrementAndGet(); return true; });

        assertTrue(fixture.runner().tickOnce());

        assertEquals(2, reads.get());
        assertEquals(1, fixture.certifications.successfulCas);
        assertEquals(ActionRunStatus.SUCCEEDED, fixture.runs.current().status());
        assertTrue(fixture.issuance.observeIssued(fixture.pending).isPresent());
        assertEquals(2, reads.get(), "bounded recovery observation must not perform a full source read");
    }

    @Test
    void rejectedFullEvidencePreventsCertificationCommitAndIsManualReviewFailure() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL);
        fixture.withFullEvidence(run -> false);

        assertTrue(fixture.runner().tickOnce());

        assertEquals(0, fixture.certifications.successfulCas);
        assertEquals(CertificationStatus.REQUESTED, fixture.certifications.current().status());
        assertEquals(ActionRunStatus.FAILED, fixture.runs.current().status());
        assertEquals("CERTIFICATION_FULL_EVIDENCE_REJECTED", fixture.runs.current().result().code());
        assertEquals(io.github.flowerjvm.flower.action.runtime.RetryDisposition.MANUAL_REVIEW,
                fixture.runs.current().result().retryDisposition());
    }

    @Test
    void fullEvidenceMutationAfterCommitCannotProduceCanonicalActionSuccess() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL);
        AtomicInteger reads = new AtomicInteger();
        fixture.withFullEvidence(run -> reads.incrementAndGet() == 1);

        assertTrue(fixture.runner().tickOnce());

        assertEquals(2, reads.get());
        assertEquals(1, fixture.certifications.successfulCas);
        assertEquals(ActionRunStatus.FAILED, fixture.runs.current().status());
        assertEquals("CERTIFICATION_FULL_EVIDENCE_REJECTED", fixture.runs.current().result().code());
    }

    @Test
    void recoveredCommittedCertificationStillRequiresFreshFullEvidence() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL);
        AtomicBoolean valid = new AtomicBoolean(true);
        fixture.withFullEvidence(run -> valid.get());
        fixture.runtime.failBeforePersistOnce = true;
        assertTrue(fixture.runner().tickOnce());
        assertEquals(CertificationStatus.CERTIFIED, fixture.certifications.current().status());
        assertEquals(ActionRunStatus.WAITING_EXTERNAL, fixture.runs.current().status());

        valid.set(false);
        fixture.clock.advance(Duration.ofSeconds(11));
        assertTrue(fixture.runner().tickOnce());

        assertEquals(1, fixture.certifications.successfulCas);
        assertEquals(ActionRunStatus.FAILED, fixture.runs.current().status());
        assertEquals("CERTIFICATION_FULL_EVIDENCE_REJECTED", fixture.runs.current().result().code());
    }

    @Test
    void fullReadExpiringClaimCannotCompleteActionAndRecoveryRechecksEvidence() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL);
        AtomicInteger reads = new AtomicInteger();
        fixture.withFullEvidence(run -> {
            if (reads.incrementAndGet() == 2) fixture.clock.advance(Duration.ofSeconds(11));
            return true;
        });

        assertTrue(fixture.runner().tickOnce());
        assertEquals(CertificationStatus.CERTIFIED, fixture.certifications.current().status());
        assertEquals(ActionRunStatus.WAITING_EXTERNAL, fixture.runs.current().status());
        assertEquals(0, fixture.runtime.completeAttempts);

        assertTrue(fixture.runner().tickOnce());
        assertEquals(3, reads.get());
        assertEquals(1, fixture.certifications.successfulCas);
        assertEquals(ActionRunStatus.SUCCEEDED, fixture.runs.current().status());
    }

    @Test
    void failedFullReadAfterLeaseExpiryCannotStealTerminalActionFromRecoveryClaim() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL);
        AtomicInteger reads = new AtomicInteger();
        fixture.withFullEvidence(run -> {
            if (reads.incrementAndGet() == 1) {
                fixture.clock.advance(Duration.ofSeconds(11));
                return false;
            }
            return true;
        });

        assertTrue(fixture.runner().tickOnce());
        assertEquals(ActionRunStatus.WAITING_EXTERNAL, fixture.runs.current().status());
        assertEquals(0, fixture.runtime.completeAttempts);
        assertEquals(0, fixture.certifications.successfulCas);

        assertTrue(fixture.runner().tickOnce());
        assertEquals(3, reads.get());
        assertEquals(ActionRunStatus.SUCCEEDED, fixture.runs.current().status());
        assertEquals(1, fixture.certifications.successfulCas);
        assertEquals(1, fixture.runtime.completeAttempts);
    }

    @Test
    void successfulFullReadCrossingLeaseDeadlineCannotCommitCertification() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL);
        AtomicInteger reads = new AtomicInteger();
        fixture.withFullEvidence(run -> {
            if (reads.incrementAndGet() == 1) fixture.clock.advance(Duration.ofSeconds(10));
            return true;
        });

        assertTrue(fixture.runner().tickOnce());
        assertEquals(0, fixture.certifications.successfulCas,
                "even an eligible result cannot commit at the exact claim lease deadline");
        assertEquals(CertificationStatus.REQUESTED, fixture.certifications.current().status());
        assertEquals(ActionRunStatus.WAITING_EXTERNAL, fixture.runs.current().status());
        assertEquals(0, fixture.runtime.completeAttempts);

        assertTrue(fixture.runner().tickOnce());
        assertEquals(3, reads.get());
        assertEquals(1, fixture.certifications.successfulCas);
        assertEquals(ActionRunStatus.SUCCEEDED, fixture.runs.current().status());
    }

    @Test
    void operationTokenAndIntentLeaseIdentitiesAreExact() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL);
        String operation = CertificationDispatchOperationIds.derive(fixture.tenant, fixture.input);
        assertEquals(operation, CertificationDispatchOperationIds.derive(fixture.tenant, fixture.input));
        assertNotEquals(
                operation,
                CertificationDispatchOperationIds.derive(new TenantId("tenant-b"), fixture.input));
        assertEquals(
                CertificationAttemptTokens.hash(" attempt-token "),
                CertificationAttemptTokens.hash("attempt-token"));

        CertificationDispatchIntent running = fixture.pending.claim(
                "claim-a", fixture.clock.instant(), Duration.ofSeconds(10));
        assertThrows(
                IllegalStateException.class,
                () -> running.claim("claim-b", fixture.clock.instant(), Duration.ofSeconds(10)));
        CertificationDispatchIntent uncertain = running.uncertain(
                "claim-a",
                CertificationDispatchRunner.PARK_UNCERTAIN,
                fixture.clock.instant(),
                fixture.clock.instant().plusMillis(250));
        assertThrows(
                IllegalStateException.class,
                () -> uncertain.claim(
                        "claim-b", fixture.clock.instant().plusMillis(249), Duration.ofSeconds(10)));
        CertificationDispatchIntent reclaimed = uncertain.claim(
                "claim-b", fixture.clock.instant().plusMillis(250), Duration.ofSeconds(10));
        CertificationDispatchIntent orphaned = reclaimed.orphanBeforeWaiting(
                "claim-b",
                CertificationDispatchRunner.ORPHANED_BEFORE_WAITING,
                fixture.clock.instant().plusMillis(250));
        assertEquals(CertificationDispatchIntentStatus.ORPHANED_BEFORE_WAITING, orphaned.status());
        assertTrue(orphaned.status().isTerminal());
        assertTrue(fixture.pending.sameImmutableIdentity(orphaned));
    }

    @Test
    void preParkCrashOrphansWithoutCertificationOrArtifacts() {
        Fixture fixture = new Fixture(ActionRunStatus.RUNNING);
        CertificationDispatchRunner runner = fixture.runner();

        assertTrue(runner.tickOnce());
        assertEquals(CertificationDispatchIntentStatus.UNCERTAIN, fixture.intents.current().status());
        assertEquals(0, fixture.artifacts.storeCalls);
        assertEquals(CertificationStatus.REQUESTED, fixture.certifications.current().status());

        fixture.clock.set(fixture.pending.createdAt().plus(Duration.ofSeconds(30)));
        assertTrue(runner.tickOnce());
        assertEquals(
                CertificationDispatchIntentStatus.ORPHANED_BEFORE_WAITING,
                fixture.intents.current().status());
        assertEquals(0, fixture.artifacts.storeCalls);
        assertEquals(0, fixture.certifications.successfulCas);
        assertEquals(0, fixture.runtime.completeAttempts);
    }

    @Test
    void uncertainActionParkIsObservedAfterBoundedBackoffWithoutBusyLoop() {
        Fixture fixture = new Fixture(ActionRunStatus.RUNNING);
        CertificationDispatchRunner runner = fixture.runner();

        assertTrue(runner.tickOnce());
        CertificationDispatchIntent uncertain = fixture.intents.current();
        assertEquals(CertificationDispatchIntentStatus.UNCERTAIN, uncertain.status());
        assertEquals(
                fixture.clock.instant().plusMillis(250), uncertain.leaseUntil().orElseThrow());

        fixture.runs.replace(fixture.action(ActionRunStatus.WAITING_EXTERNAL, null));
        assertFalse(runner.tickOnce());
        fixture.clock.advance(Duration.ofMillis(249));
        assertFalse(runner.tickOnce());
        assertEquals(0, fixture.artifacts.storeCalls);

        fixture.clock.advance(Duration.ofMillis(1));
        assertTrue(runner.tickOnce());
        assertEquals(CertificationStatus.CERTIFIED, fixture.certifications.current().status());
        assertEquals(CertificationDispatchIntentStatus.COMPLETED, fixture.intents.current().status());
        assertEquals(ActionRunStatus.SUCCEEDED, fixture.runs.current().status());
    }

    @Test
    void artifactStoredBeforeCertificationCasConvergesOnRetry() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL);
        fixture.certifications.failNextCas = true;
        CertificationDispatchRunner runner = fixture.runner();

        assertTrue(runner.tickOnce());
        assertEquals(List.of("evidence", "manifest"), fixture.artifacts.storeOrder);
        assertEquals(CertificationStatus.REQUESTED, fixture.certifications.current().status());
        assertEquals(CertificationDispatchIntentStatus.RUNNING, fixture.intents.current().status());

        fixture.clock.advance(Duration.ofSeconds(11));
        assertTrue(runner.tickOnce());
        CertificationIssuanceOutcome issued = fixture.issuance
                .observeIssued(fixture.pending)
                .orElseThrow();
        assertFalse(issued.issuedNow());
        assertEquals(2, fixture.artifacts.storeCalls);
        assertEquals(1, fixture.certifications.successfulCas);
        assertEquals(NOW, issued.certification().issuedAt().orElseThrow());
        assertEquals(fixture.clock.instant(), issued.certification().updatedAt());
        assertTrue(issued.certification().expiresAt().isEmpty());
        assertEquals(CertificationDispatchIntentStatus.COMPLETED, fixture.intents.current().status());
        assertEquals(ActionRunStatus.SUCCEEDED, fixture.runs.current().status());
    }

    @Test
    void certificationCasBeforeActionCompletionConvergesWithoutSecondIssue() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL);
        fixture.runtime.failBeforePersistOnce = true;
        CertificationDispatchRunner runner = fixture.runner();

        assertTrue(runner.tickOnce());
        assertEquals(CertificationStatus.CERTIFIED, fixture.certifications.current().status());
        assertEquals(ActionRunStatus.WAITING_EXTERNAL, fixture.runs.current().status());
        assertEquals(CertificationDispatchIntentStatus.RUNNING, fixture.intents.current().status());
        assertEquals(1, fixture.certifications.successfulCas);
        assertEquals(2, fixture.artifacts.storeCalls);

        fixture.clock.advance(Duration.ofSeconds(11));
        assertTrue(runner.tickOnce());
        assertEquals(ActionRunStatus.SUCCEEDED, fixture.runs.current().status());
        assertEquals(CertificationDispatchIntentStatus.COMPLETED, fixture.intents.current().status());
        assertEquals(1, fixture.certifications.successfulCas);
        assertEquals(2, fixture.artifacts.storeCalls);
        assertEquals(2, fixture.runtime.completeAttempts);
        assertEquals(1, fixture.runtime.successfulCompletions);
    }

    @Test
    void actionCompletionPersistedBeforeIntentCasConvergesWithoutSecondIssue() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL);
        fixture.runtime.failAfterPersistOnce = true;
        CertificationDispatchRunner runner = fixture.runner();

        assertTrue(runner.tickOnce());
        assertEquals(CertificationStatus.CERTIFIED, fixture.certifications.current().status());
        assertEquals(ActionRunStatus.SUCCEEDED, fixture.runs.current().status());
        assertEquals(CertificationDispatchIntentStatus.RUNNING, fixture.intents.current().status());

        fixture.clock.advance(Duration.ofSeconds(11));
        assertTrue(runner.tickOnce());
        assertEquals(CertificationDispatchIntentStatus.COMPLETED, fixture.intents.current().status());
        assertEquals(1, fixture.certifications.successfulCas);
        assertEquals(2, fixture.artifacts.storeCalls);
        assertEquals(1, fixture.runtime.completeAttempts);
        assertEquals(1, fixture.runtime.successfulCompletions);
    }

    @Test
    void wrongActionOwnerNeverIssues() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL);
        fixture.runs.replace(fixture.runs.current().toBuilder()
                .actionId("factory.untrusted.issue")
                .build());

        assertTrue(fixture.runner().tickOnce());
        assertEquals(CertificationDispatchIntentStatus.ORPHANED, fixture.intents.current().status());
        assertEquals(CertificationStatus.REQUESTED, fixture.certifications.current().status());
        assertEquals(0, fixture.artifacts.storeCalls);
        assertEquals(0, fixture.runtime.completeAttempts);
    }

    @Test
    void terminalActionConflictIsOrphanedWithoutIssuance() {
        Fixture fixture = new Fixture(ActionRunStatus.SUCCEEDED);

        assertTrue(fixture.runner().tickOnce());
        assertEquals(CertificationDispatchIntentStatus.ORPHANED, fixture.intents.current().status());
        assertEquals(
                CertificationDispatchRunner.ACTION_TERMINAL_CONFLICT,
                fixture.intents.current().lastCode().orElseThrow());
        assertEquals(CertificationStatus.REQUESTED, fixture.certifications.current().status());
        assertEquals(0, fixture.artifacts.storeCalls);
    }

    @Test
    void sessionChangeAfterActionParkBlocksIssuanceWithoutArtifacts() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL);
        fixture.sessions.replace(fixture.session.requestCancellation(fixture.clock.instant()));

        assertTrue(fixture.runner().tickOnce());
        assertEquals(CertificationStatus.REQUESTED, fixture.certifications.current().status());
        assertEquals(0, fixture.artifacts.storeCalls);
        assertEquals(ActionRunStatus.FAILED, fixture.runs.current().status());
        assertEquals(CertificationDispatchIntentStatus.COMPLETED, fixture.intents.current().status());
        assertEquals(
                CertificationDispatchRunner.BUILD_SESSION_NOT_CERTIFIABLE,
                fixture.runs.current().result().code());
    }

    @Test
    void cancellationBetweenRunnerObservationAndFinalCommitNeverCertifies() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL);
        fixture.issuanceTransaction.beforeCommitOnce(() -> fixture.sessions.replace(
                fixture.session.requestCancellation(fixture.clock.instant())));
        CertificationDispatchRunner runner = fixture.runner();

        assertTrue(runner.tickOnce());
        assertEquals(CertificationStatus.REQUESTED, fixture.certifications.current().status());
        assertEquals(BuildSessionStatus.CANCELLING,
                fixture.sessions.find(fixture.tenant, fixture.buildSessionId).orElseThrow().status());
        assertEquals(CertificationDispatchIntentStatus.RUNNING, fixture.intents.current().status());
        assertEquals(ActionRunStatus.WAITING_EXTERNAL, fixture.runs.current().status());
        assertEquals(2, fixture.artifacts.storeCalls,
                "content-addressed staging may precede the denied atomic commit");

        fixture.clock.advance(Duration.ofSeconds(11));
        assertTrue(runner.tickOnce());
        assertEquals(CertificationStatus.REQUESTED, fixture.certifications.current().status());
        assertEquals(ActionRunStatus.FAILED, fixture.runs.current().status());
        assertEquals(CertificationDispatchIntentStatus.COMPLETED, fixture.intents.current().status());
        assertEquals(CertificationDispatchRunner.BUILD_SESSION_NOT_CERTIFIABLE,
                fixture.runs.current().result().code());
    }

    @Test
    void concurrentLeaseClaimsProduceOneCertificationCasAndOneActionCompletion() throws Exception {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL);
        CertificationDispatchRunner first = fixture.runner();
        CertificationDispatchRunner second = fixture.runner();
        CyclicBarrier start = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> one = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return first.tickOnce();
            });
            Future<Boolean> two = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return second.tickOnce();
            });
            int claimed = (one.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (two.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, claimed);
        }

        assertEquals(CertificationStatus.CERTIFIED, fixture.certifications.current().status());
        assertEquals(CertificationDispatchIntentStatus.COMPLETED, fixture.intents.current().status());
        assertEquals(1, fixture.certifications.successfulCas);
        assertEquals(2, fixture.artifacts.storeCalls);
        assertEquals(1, fixture.runtime.successfulCompletions);
    }

    private static final class Fixture {
        private final TenantId tenant = new TenantId("tenant-a");
        private final BuildSessionId buildSessionId = new BuildSessionId("build-certification-001");
        private final CandidateId candidateId = new CandidateId("candidate-certification-001");
        private final CertificationId certificationId = new CertificationId("certification-001");
        private final CertificationArtifactLock source = lock("source");
        private final CertificationArtifactLock dependency = lock("dependency");
        private final CertificationArtifactLock toolchain = lock("toolchain");
        private final CertificationArtifactLock generationInput = lock("generation-input");
        private final CertificationArtifactLock productContract = lock("product-contract");
        private final CertificationArtifactLock apiSignature = lock("api-signature");
        private final CertificationArtifactLock verificationResult = lock("verification-result");
        private final CertificationArtifactLock policySnapshot = lock("policy-snapshot");
        private final CertificationArtifactLock compatibility = lock("compatibility");
        private final ContentHash fixtureSet = hash("fixture-set");
        private final CertificationArtifactLock inputArtifact = lock("certification-input");
        private final CertificationInputLock inputLock = new CertificationInputLock(
                CertificationInputLock.SCHEMA_VERSION,
                tenant,
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                buildSessionId,
                new WorkOrderId("work-certification-001"),
                candidateId,
                source.hash(),
                source,
                dependency,
                toolchain,
                generationInput,
                productContract,
                apiSignature,
                "sha256-ordinal-v1",
                "internal",
                new VerificationRunId("verification-certification-001"),
                "verification-action-001",
                verificationResult,
                fixtureSet,
                policySnapshot,
                compatibility,
                "internal",
                "0.2.0",
                "0.1.3",
                "0.3.3");
        private final Certification requested = Certification.requested(
                certificationId, inputLock, inputArtifact, NOW);
        private final CertificationIssueInput input = new CertificationIssueInput(
                certificationId, inputArtifact.hash(), requested.version());
        private final AgentPackCertificationPolicy trustedPolicy = new AgentPackCertificationPolicy(
                productContract,
                "internal",
                fixtureSet,
                "sha256-ordinal-v1",
                "internal",
                "0.2.0",
                "0.1.3",
                "0.3.3");
        private final BuildSession session = buildSession();
        private final MutableClock clock = new MutableClock(NOW.plusSeconds(2));
        private final MemoryCertificationRepository certifications =
                new MemoryCertificationRepository(requested);
        private final MemoryArtifactStore artifacts = new MemoryArtifactStore();
        private final RecordingCodec codec = new RecordingCodec();
        private final CertificationDispatchIntent pending = CertificationDispatchIntent.pending(
                CertificationDispatchOperationIds.derive(tenant, input),
                tenant,
                certificationId,
                input.inputLockManifestHash(),
                input.expectedCertificationVersion(),
                "certification-action-001",
                CertificationAttemptTokens.hash("attempt-token"),
                session.deadlineAt(),
                NOW.plusSeconds(1));
        private final MemoryIntentRepository intents = new MemoryIntentRepository(pending);
        private final MemoryRunStore runs;
        private final FakeRuntime runtime;
        private final MemoryBuildSessionRepository sessions =
                new MemoryBuildSessionRepository(session);
        private final MemoryCertificationIssuanceTransaction issuanceTransaction =
                new MemoryCertificationIssuanceTransaction(certifications, sessions);
        private CertificationIssuanceService issuance;

        private Fixture(ActionRunStatus actionStatus) {
            issuance = new CertificationIssuanceService(
                    certifications, issuanceTransaction, artifacts, codec, clock);
            runs = new MemoryRunStore(action(actionStatus, actionStatus == ActionRunStatus.SUCCEEDED
                    ? ActionExecutionResult.succeeded(Map.of("wrong", "result"))
                    : null));
            runtime = new FakeRuntime(runs);
        }

        private void withFullEvidence(VerificationEvidenceValidator fullEvidence) {
            VerificationRun verification = new VerificationRun(
                    inputLock.verificationRunId(), tenant, buildSessionId, candidateId, source.hash(),
                    inputLock.gateProfile(), toolchain.hash(), fixtureSet, VerificationRunStatus.PASSED,
                    Optional.of(verificationResult.reference()), Optional.of(verificationResult.hash()),
                    Optional.of("VERIFIED"), Optional.of(VerificationDisposition.REVIEW_ELIGIBLE),
                    Optional.of(NOW), Optional.of(NOW.plusSeconds(1)), 2, NOW, NOW.plusSeconds(1));
            VerificationRunRepository repository = new VerificationRunRepository() {
                @Override public void create(VerificationRun ignored) { throw new UnsupportedOperationException(); }
                @Override public Optional<VerificationRun> find(TenantId scopedTenant, VerificationRunId id) {
                    return tenant.equals(scopedTenant) && verification.verificationRunId().equals(id)
                            ? Optional.of(verification) : Optional.empty();
                }
                @Override public Optional<VerificationRun> findLatestForCandidate(
                        TenantId tenantId, BuildSessionId sessionId, CandidateId candidate,
                        ContentHash candidateHash, String gateProfile) { throw new UnsupportedOperationException(); }
                @Override public boolean compareAndSet(VerificationRun expected, VerificationRun next) {
                    throw new UnsupportedOperationException();
                }
            };
            issuance = new CertificationIssuanceService(certifications, issuanceTransaction, artifacts,
                    codec, clock, repository, fullEvidence, run -> VerificationActionEvidenceOwner.Assessment.canonical());
        }

        private CertificationDispatchRunner runner() {
            return new CertificationDispatchRunner(
                    intents,
                    certifications,
                    sessions,
                    trustedPolicy,
                    issuance,
                    runtime,
                    runs,
                    clock,
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(30));
        }

        private BuildSession buildSession() {
            return new BuildSession(
                    buildSessionId,
                    tenant,
                    new ProjectId("project-certification-001"),
                    ProductLineId.AGENT_PACK,
                    "request-certification-001",
                    "factory-service",
                    BuildSessionStatus.CERTIFYING,
                    BuildSessionPhase.CERTIFY,
                    new ArtifactReference("artifact:requirements"),
                    hash("requirements"),
                    Optional.of("manager"),
                    Optional.of("coding"),
                    Optional.empty(),
                    Optional.of(candidateId),
                    Optional.of(source.hash()),
                    Optional.empty(),
                    0,
                    3,
                    NOW,
                    NOW.plusSeconds(3600),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    4,
                    NOW,
                    NOW.plusSeconds(1));
        }

        private ActionRun action(ActionRunStatus status, ActionExecutionResult result) {
            return ActionRun.builder()
                    .runId(pending.actionRunId())
                    .version(status == ActionRunStatus.SUCCEEDED ? 3 : 2)
                    .tenantId(tenant.value())
                    .userId("certification-principal")
                    .traceId("trace-certification-001")
                    .contextMetadata(Map.of(
                            "actor.permissions", Set.of(CertificationIssueAction.PERMISSION),
                            "resource.type", CertificationIssueAction.RESOURCE_TYPE,
                            "resource.id", certificationId.value()))
                    .actionId(CertificationIssueAction.ACTION_ID)
                    .proposalId("proposal-certification-001")
                    .requesterId("factory-service")
                    .requestChannel(ActionRequestChannel.INTERNAL)
                    .proposerType(ActionProposerType.SERVICE)
                    .input(input.toMap())
                    .duplicateKey(CertificationIssueIdempotencyKeys.derive(
                            requested, input.expectedCertificationVersion()))
                    .status(status)
                    .currentStage(status.isTerminal() ? "TERMINAL" : "EXECUTION")
                    .attemptToken("attempt-token")
                    .externalOperationId(status == ActionRunStatus.RUNNING ? "" : pending.operationId())
                    .externalOperationMetadata(status == ActionRunStatus.RUNNING
                            ? Map.of()
                            : Map.of(
                                    "dispatchMode", "durable-certification-intent",
                                    CertificationIssueAction.CERTIFICATION_ID,
                                    certificationId.value()))
                    .dueAt(status == ActionRunStatus.RUNNING ? null : pending.deadlineAt())
                    .result(result)
                    .createdAt(NOW.plusSeconds(1))
                    .updatedAt(NOW.plusSeconds(1))
                    .build();
        }
    }

    private static final class MemoryCertificationRepository implements CertificationRepository {
        private Certification current;
        private boolean failNextCas;
        private int successfulCas;

        private MemoryCertificationRepository(Certification current) {
            this.current = current;
        }

        @Override
        public synchronized void create(Certification certification) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized Optional<Certification> find(
                TenantId tenantId, CertificationId certificationId) {
            return current.inputLock().tenantId().equals(tenantId)
                            && current.certificationId().equals(certificationId)
                    ? Optional.of(current)
                    : Optional.empty();
        }

        @Override
        public synchronized boolean compareAndSet(Certification expected, Certification next) {
            if (failNextCas) {
                failNextCas = false;
                throw new IllegalStateException("injected crash before Certification CAS");
            }
            if (!current.equals(expected)) {
                return false;
            }
            current = next;
            successfulCas++;
            return true;
        }

        private synchronized Certification current() {
            return current;
        }
    }

    private static final class MemoryIntentRepository implements CertificationDispatchIntentRepository {
        private CertificationDispatchIntent current;

        private MemoryIntentRepository(CertificationDispatchIntent current) {
            this.current = current;
        }

        @Override
        public synchronized void create(CertificationDispatchIntent intent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized Optional<CertificationDispatchIntent> find(String operationId) {
            return current.operationId().equals(operationId) ? Optional.of(current) : Optional.empty();
        }

        @Override
        public synchronized Optional<CertificationDispatchIntent> findLatest(
                TenantId tenantId, CertificationId certificationId) {
            return current.tenantId().equals(tenantId)
                            && current.certificationId().equals(certificationId)
                    ? Optional.of(current)
                    : Optional.empty();
        }

        @Override
        public synchronized Optional<CertificationDispatchIntent> claimNext(
                Instant now, Duration lease, String claimToken) {
            boolean due = current.status() == CertificationDispatchIntentStatus.PENDING
                    || (current.status() == CertificationDispatchIntentStatus.UNCERTAIN
                            && current.leaseUntil().filter(value -> !now.isBefore(value)).isPresent());
            if (!due) {
                return Optional.empty();
            }
            current = current.claim(claimToken, now, lease);
            return Optional.of(current);
        }

        @Override
        public synchronized Optional<CertificationDispatchIntent> claimExpiredRunning(
                Instant now, Duration lease, String claimToken) {
            if (current.status() != CertificationDispatchIntentStatus.RUNNING
                    || current.leaseUntil().filter(value -> !now.isBefore(value)).isEmpty()) {
                return Optional.empty();
            }
            current = current.claim(claimToken, now, lease);
            return Optional.of(current);
        }

        @Override
        public synchronized boolean compareAndSet(
                CertificationDispatchIntent expected, CertificationDispatchIntent next) {
            if (!current.equals(expected)) {
                return false;
            }
            current = next;
            return true;
        }

        private synchronized CertificationDispatchIntent current() {
            return current;
        }
    }

    private static final class MemoryCertificationIssuanceTransaction
            implements CertificationIssuanceTransaction {
        private final MemoryCertificationRepository certifications;
        private final MemoryBuildSessionRepository sessions;
        private Runnable beforeCommit = () -> {};

        private MemoryCertificationIssuanceTransaction(
                MemoryCertificationRepository certifications,
                MemoryBuildSessionRepository sessions) {
            this.certifications = certifications;
            this.sessions = sessions;
        }

        @Override
        public synchronized CertificationIssuanceCommit commit(
                CertificationDispatchIntent intent,
                Certification expected,
                Certification proposed) {
            Runnable hook = beforeCommit;
            beforeCommit = () -> {};
            hook.run();

            Certification current = certifications
                    .find(intent.tenantId(), intent.certificationId())
                    .orElseThrow();
            if (current.equals(proposed)) {
                return new CertificationIssuanceCommit(current, false);
            }
            if (!current.equals(expected)) {
                throw new IllegalStateException("Certification changed before final commit");
            }
            BuildSession session = sessions
                    .find(intent.tenantId(), expected.inputLock().buildSessionId())
                    .orElseThrow();
            boolean live = session.productLineId().equals(ProductLineId.AGENT_PACK)
                    && session.status() == BuildSessionStatus.CERTIFYING
                    && session.currentPhase() == BuildSessionPhase.CERTIFY
                    && session.currentCandidateId()
                            .filter(expected.inputLock().candidateId()::equals).isPresent()
                    && session.currentCandidateHash()
                            .filter(expected.inputLock().candidateHash()::equals).isPresent()
                    && session.currentCertificationId().isEmpty()
                    && session.cancellationRequestedAt().isEmpty()
                    && session.deadlineAt().equals(intent.deadlineAt())
                    && proposed.updatedAt().isBefore(session.deadlineAt());
            if (!live) {
                throw new IllegalStateException("BuildSession lost Certification authority");
            }
            if (!certifications.compareAndSet(expected, proposed)) {
                Certification canonical = certifications.current();
                if (canonical.equals(proposed)) {
                    return new CertificationIssuanceCommit(canonical, false);
                }
                throw new IllegalStateException("Certification CAS conflict");
            }
            return new CertificationIssuanceCommit(proposed, true);
        }

        private synchronized void beforeCommitOnce(Runnable hook) {
            beforeCommit = hook;
        }
    }

    private static final class MemoryArtifactStore implements ArtifactStore {
        private final Map<String, Artifact> stored = new java.util.LinkedHashMap<>();
        private final List<String> storeOrder = new ArrayList<>();
        private int storeCalls;

        @Override
        public synchronized ArtifactReference store(Artifact artifact) {
            String key = key(artifact.tenantId(), artifact.reference());
            Artifact existing = stored.get(key);
            if (existing != null && (!existing.contentHash().equals(artifact.contentHash())
                    || !existing.mediaType().equals(artifact.mediaType())
                    || !Arrays.equals(existing.content(), artifact.content()))) {
                throw new IllegalStateException("artifact conflict");
            }
            storeCalls++;
            storeOrder.add(artifact.reference().value().contains("/evidence/")
                    ? "evidence"
                    : "manifest");
            stored.putIfAbsent(key, artifact);
            return artifact.reference();
        }

        @Override
        public synchronized Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            return Optional.ofNullable(stored.get(key(tenantId, reference)));
        }

        private static String key(TenantId tenantId, ArtifactReference reference) {
            return tenantId.value() + '|' + reference.value();
        }
    }

    private static final class RecordingCodec implements CertificationArtifactCodec {
        @Override
        public byte[] writeInputLock(CertificationInputLock value) {
            return bytes(value);
        }

        @Override
        public CertificationInputLock readInputLock(byte[] content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] writeEvidence(CertificationEvidenceManifest value) {
            return bytes(value);
        }

        @Override
        public CertificationEvidenceManifest readEvidence(byte[] content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] writeComponentManifest(CertifiedAgentComponentManifest value) {
            return bytes(value);
        }

        @Override
        public CertifiedAgentComponentManifest readComponentManifest(byte[] content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] writeCompatibilityDescriptor(AgentPackCompatibilityDescriptor value) {
            return bytes(value);
        }

        @Override
        public AgentPackCompatibilityDescriptor readCompatibilityDescriptor(byte[] content) {
            throw new UnsupportedOperationException();
        }

        private static byte[] bytes(Object value) {
            return value.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final class MemoryBuildSessionRepository implements BuildSessionRepository {
        private BuildSession session;

        private MemoryBuildSessionRepository(BuildSession session) {
            this.session = session;
        }

        @Override
        public void create(BuildSession ignored) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized Optional<BuildSession> find(TenantId tenantId, BuildSessionId buildSessionId) {
            return session.tenantId().equals(tenantId)
                            && session.buildSessionId().equals(buildSessionId)
                    ? Optional.of(session)
                    : Optional.empty();
        }

        @Override
        public boolean compareAndSet(BuildSession expected, BuildSession next) {
            throw new UnsupportedOperationException();
        }

        private synchronized void replace(BuildSession replacement) {
            session = replacement;
        }
    }

    private static final class MemoryRunStore implements RunStore {
        private ActionRun current;

        private MemoryRunStore(ActionRun current) {
            this.current = current;
        }

        @Override
        public synchronized ActionRun create(ActionRun run) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized Optional<ActionRun> find(String runId) {
            return current.runId().equals(runId) ? Optional.of(current) : Optional.empty();
        }

        @Override
        public synchronized boolean compareAndSet(ActionRun expected, ActionRun updated) {
            if (!current.equals(expected)) {
                return false;
            }
            current = updated;
            return true;
        }

        @Override
        public synchronized List<ActionRun> findResumable(String tenantId) {
            return current.tenantId().equals(tenantId) ? List.of(current) : List.of();
        }

        private synchronized void replace(ActionRun replacement) {
            current = replacement;
        }

        private synchronized ActionRun current() {
            return current;
        }
    }

    private static final class FakeRuntime implements CompletableActionRuntime {
        private final MemoryRunStore runs;
        private boolean failBeforePersistOnce;
        private boolean failAfterPersistOnce;
        private int completeAttempts;
        private int successfulCompletions;

        private FakeRuntime(MemoryRunStore runs) {
            this.runs = runs;
        }

        @Override
        public synchronized ActionExecutionResult complete(
                String runId, String attemptToken, ActionExecutionResult result) {
            completeAttempts++;
            ActionRun current = runs.find(runId).orElseThrow();
            if (current.status() != ActionRunStatus.WAITING_EXTERNAL
                    || !current.attemptToken().equals(attemptToken)) {
                throw new IllegalStateException("Action is not owned by the current deferred attempt");
            }
            if (failBeforePersistOnce) {
                failBeforePersistOnce = false;
                throw new IllegalStateException("injected crash before Action completion persistence");
            }
            ActionRun next = current.toBuilder()
                    .version(current.version() + 1)
                    .status(result.status() == ActionExecutionStatus.SUCCEEDED
                            ? ActionRunStatus.SUCCEEDED
                            : ActionRunStatus.FAILED)
                    .currentStage("TERMINAL")
                    .result(result)
                    .updatedAt(current.updatedAt().plusMillis(1))
                    .build();
            if (!runs.compareAndSet(current, next)) {
                throw new IllegalStateException("Action completion CAS lost");
            }
            successfulCompletions++;
            if (failAfterPersistOnce) {
                failAfterPersistOnce = false;
                throw new IllegalStateException("injected crash after Action completion persistence");
            }
            return result;
        }

        @Override
        public ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionExecutionResult resume(String runId, ApprovalDecision decision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionExecutionResult cancel(String runId, String reason) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant initial) {
            now = new AtomicReference<>(initial);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock is UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        private void set(Instant value) {
            now.set(value);
        }

        private void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }
    }

    private static CertificationArtifactLock lock(String name) {
        return new CertificationArtifactLock(
                new ArtifactReference("artifact:" + name), hash(name));
    }

    private static ContentHash hash(String material) {
        try {
            return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
