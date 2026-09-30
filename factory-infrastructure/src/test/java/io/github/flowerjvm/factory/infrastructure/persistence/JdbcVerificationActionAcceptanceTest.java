package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.action.VerificationRunAction;
import io.github.flowerjvm.factory.application.action.VerificationRunActionExecutor;
import io.github.flowerjvm.factory.application.action.VerificationRunActionValidator;
import io.github.flowerjvm.factory.application.action.VerificationRunIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.VerificationRunInput;
import io.github.flowerjvm.factory.application.action.VerificationRunPolicyGate;
import io.github.flowerjvm.factory.application.action.VerificationRunPreExecutionGuard;
import io.github.flowerjvm.factory.application.action.VerificationRunVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.verification.ActionBackedVerificationRunLauncher;
import io.github.flowerjvm.factory.application.verification.ActionRuntimeVerificationEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationExecutionService;
import io.github.flowerjvm.factory.application.verification.VerificationAttemptTokens;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntent;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntentStatus;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchOperationIds;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchRunner;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchTransaction;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.verification.VerificationResult;
import io.github.flowerjvm.factory.contracts.verification.VerificationStableCodes;
import io.github.flowerjvm.factory.contracts.verification.VerificationStatus;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.AuditSink;
import io.github.flowerjvm.flower.action.runtime.audit.TraceSink;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionDecision;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionDecisionType;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** JDBC full-pipeline acceptance for the PR4 governed verification Action. */
class JdbcVerificationActionAcceptanceTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final ContentHash FIXTURE_HASH = hash("fixture-set");

    @Test
    void nanosecondClaimsReturnCanonicalJdbcSnapshotsWithoutRelaxingLeaseOrStaleOwnerFences() {
        assertCanonicalClaimPrecision(FactoryDatabaseMigrationsTest.h2("verification-claim-precision"));
    }

    @Test
    void nanosecondBlockedStartReturnsCanonicalOrphanWithoutVerifierEffects() {
        assertCanonicalBlockedClaim(FactoryDatabaseMigrationsTest.h2("verification-blocked-claim-precision"));
    }

    /** Shared with native PostgreSQL: synthetic dispatch rows are not product verification evidence. */
    static void assertCanonicalClaimPrecision(DataSource database) {
        Fixture fixture = fixture(database, "precision", "candidate-precision");
        try {
            var owner = runningActionOwner(fixture, fixture.run(), "action-precision", "attempt-precision");
            storeActionOwner(fixture, owner);
            var intents = new JdbcVerificationDispatchIntentRepository(database);
            var pending = new JdbcVerificationDispatchTransaction(database).prepare(
                    fixture.run().tenantId(), ownerInput(owner), owner.runId(),
                    VerificationAttemptTokens.hash(owner.attemptToken()), NOW);
            Instant firstNow = NOW.plusSeconds(1).plusNanos(123_456_789);
            Duration lease = Duration.ofSeconds(5).plusNanos(211);
            var first = intents.claimNext(firstNow, lease, "precision-first").orElseThrow();
            assertCanonicalClaim(intents, first);
            assertNotEquals(firstNow, first.updatedAt(), "fixture must exercise actual JDBC precision loss");
            assertEquals(pending.version() + 1, first.version());
            assertEquals(1, first.attemptCount());
            Instant firstExpiry = first.leaseUntil().orElseThrow();
            assertTrue(intents.claimExpiredRunningForReconciliation(
                    firstExpiry.minusNanos(1_000), lease, "too-early").isEmpty());
            assertEquals(first, intents.find(first.operationId()).orElseThrow());

            var recovered = intents.claimExpiredRunningForReconciliation(
                    firstExpiry.plusNanos(789), lease, "precision-recovery").orElseThrow();
            assertCanonicalClaim(intents, recovered);
            assertEquals(first.version() + 1, recovered.version());
            assertEquals(2, recovered.attemptCount());
            assertEquals(Optional.of("precision-recovery"), recovered.claimToken());
            assertFalse(intents.compareAndSet(first, first.complete(
                    "precision-first", "STALE_OWNER", recovered.updatedAt())));
            assertEquals(recovered, intents.find(recovered.operationId()).orElseThrow());

            var uncertain = recovered.uncertain("precision-recovery", "PARK_UNCERTAIN",
                    recovered.updatedAt().plusSeconds(1), recovered.updatedAt().plusSeconds(2));
            assertTrue(intents.compareAndSet(recovered, uncertain));
            var persistedUncertain = intents.find(recovered.operationId()).orElseThrow();
            assertTrue(intents.claimNext(persistedUncertain.leaseUntil().orElseThrow().minusNanos(1_000),
                    lease, "uncertain-too-early").isEmpty());
            var resumed = intents.claimNext(persistedUncertain.leaseUntil().orElseThrow().plusNanos(123),
                    lease, "precision-resumed").orElseThrow();
            assertCanonicalClaim(intents, resumed);
            assertEquals(3, resumed.attemptCount());
            assertEquals(Optional.of("PARK_UNCERTAIN"), resumed.lastCode());
            assertEquals(0, fixture.verifierCalls().get(), "claim/readback alone must not run verification");
        } finally {
            fixture.executor().shutdownNow();
        }
    }

    static void assertCanonicalBlockedClaim(DataSource database) {
        Fixture fixture = fixture(database, "precision-blocked", "candidate-precision-blocked");
        try {
            var owner = runningActionOwner(fixture, fixture.run(), "action-precision-blocked", "attempt-blocked");
            storeActionOwner(fixture, owner);
            var intents = new JdbcVerificationDispatchIntentRepository(database);
            new JdbcVerificationDispatchTransaction(database).prepare(
                    fixture.run().tenantId(), ownerInput(owner), owner.runId(),
                    VerificationAttemptTokens.hash(owner.attemptToken()), NOW);
            var session = fixture.buildSessions().find(fixture.run().tenantId(), fixture.run().buildSessionId()).orElseThrow();
            assertTrue(fixture.buildSessions().compareAndSet(session, session.requestCancellation(NOW)));
            var blocked = intents.claimNext(NOW.plusNanos(789), Duration.ofSeconds(5), "blocked-owner").orElseThrow();
            assertEquals(VerificationDispatchIntentStatus.ORPHANED, blocked.status());
            assertEquals("VERIFICATION_DISPATCH_NO_LONGER_LAUNCHABLE", blocked.lastCode().orElseThrow());
            assertEquals(blocked, intents.find(blocked.operationId()).orElseThrow());
            assertEquals(0, blocked.updatedAt().getNano() % 1_000);
            assertTrue(blocked.claimToken().isEmpty());
            assertEquals(0, fixture.verifierCalls().get());
        } finally {
            fixture.executor().shutdownNow();
        }
    }

    private static void assertCanonicalClaim(JdbcVerificationDispatchIntentRepository intents,
            VerificationDispatchIntent claimed) {
        assertEquals(VerificationDispatchIntentStatus.RUNNING, claimed.status());
        assertEquals(claimed, intents.find(claimed.operationId()).orElseThrow());
        // These are the exact snapshot equality predicates used by receipt recording and completion.
        assertTrue(intents.findLatest(claimed.tenantId(), claimed.verificationRunId()).filter(claimed::equals).isPresent());
        assertEquals(0, claimed.updatedAt().getNano() % 1_000);
        assertEquals(0, claimed.leaseUntil().orElseThrow().getNano() % 1_000);
    }

    @Test
    void concurrentFullPipelineDuplicateInvokesVerifierOnceAndCachesFirstTerminalResult() throws Exception {
        Fixture fixture = fixture("verification-action-race", "candidate-race");
        AtomicInteger accepts = new AtomicInteger();
        var transitionRace = new TransitionBarrierDuplicatePolicy(new ObservedDuplicatePolicy(
                duplicatePolicy(fixture), accepts));
        CountDownLatch verifierGate = new CountDownLatch(1);
        Executor gatedExecutor = command -> fixture.executor().execute(() -> {
            try {
                if (!verifierGate.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("verifier gate timed out");
                }
                command.run();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("verifier gate interrupted", exception);
            }
        });
        DefaultActionRuntime first = runtime(fixture, transitionRace, gatedExecutor);
        DefaultActionRuntime replayRuntime = runtime(fixture, transitionRace);
        VerificationRun run = fixture.run();

        ActionExecutionResult firstResult = first.handle(
                proposal(fixture, run, "proposal-a"), context(fixture, "action-run-a", true));
        assertEquals(ActionExecutionStatus.ACCEPTED, firstResult.status());
        transitionRace.enable();
        verifierGate.countDown();
        ActionExecutionResult racingReplay = replayRuntime.handle(
                proposal(fixture, run, "proposal-b"), context(fixture, "action-run-b", true));

        assertEquals(2, transitionRace.transitionEntrants());
        assertEquals(1, accepts.get());
        assertEquals(1, fixture.verifierCalls().get());
        if (racingReplay.status() != ActionExecutionStatus.SUCCEEDED) {
            assertEquals(ActionExecutionStatus.DENIED, racingReplay.status());
            assertEquals("DUPLICATE_ACTION", racingReplay.code());
        }
        waitForTerminal(fixture);
        VerificationRun terminal = fixture.verifications()
                .find(run.tenantId(), run.verificationRunId())
                .orElseThrow();
        assertEquals(VerificationRunStatus.PASSED, terminal.status());

        ActionExecutionResult late = runtime(fixture, duplicatePolicy(fixture)).handle(
                proposal(fixture, run, "proposal-late"), context(fixture, "action-run-late", true));
        assertEquals(ActionExecutionStatus.SUCCEEDED, late.status());
        assertEquals(1, fixture.verifierCalls().get());
        assertEquals(terminal, fixture.verifications()
                .find(run.tenantId(), run.verificationRunId())
                .orElseThrow());
    }

    @Test
    void deniedPrincipalCannotReadAuthorizedCachedVerificationResult() throws Exception {
        Fixture fixture = fixture("verification-action-denied", "candidate-denied");
        DefaultActionRuntime runtime = runtime(fixture, duplicatePolicy(fixture));
        VerificationRun run = fixture.run();
        ActionExecutionResult authorized = runtime.handle(
                proposal(fixture, run, "proposal-authorized"), context(fixture, "action-authorized", true));
        assertEquals(ActionExecutionStatus.ACCEPTED, authorized.status(), authorized::toString);
        waitForTerminal(fixture);

        ActionExecutionResult denied = runtime(fixture, duplicatePolicy(fixture)).handle(
                proposal(fixture, run, "proposal-denied"), context(fixture, "action-denied", false));

        assertEquals(ActionExecutionStatus.DENIED, denied.status());
        assertFalse(denied.output().containsKey(VerificationRunAction.VERIFICATION_RUN_ID));
        assertFalse(denied.output().containsKey("resultManifestRef"));
        assertEquals(1, fixture.verifierCalls().get());
    }

    @Test
    void sameTenantKeyForDifferentCandidateDoesNotReturnOtherResourceEvidence() throws Exception {
        Fixture first = fixture("verification-action-resource", "candidate-a");
        DefaultActionRuntime runtime = runtime(first, duplicatePolicy(first));
        VerificationRun runA = first.run();
        ActionExecutionResult resourceA = runtime.handle(
                proposal(first, runA, "proposal-resource-a"), context(first, "action-resource-a", true));
        assertEquals(ActionExecutionStatus.ACCEPTED, resourceA.status(), resourceA::toString);
        waitForTerminal(first);

        CandidateVersion candidateB = persistCandidate(
                first,
                "candidate-b",
                first.candidate().dependencyLockRef(),
                first.candidate().dependencyLockHash());
        rebindSession(first, candidateB);
        VerificationRun runB = requested(first, candidateB, "verification-resource-b");
        first.verifications().create(runB);
        ActionExecutionResult resourceB = runtime.handle(
                proposal(first, runB, "proposal-resource-b"), context(first, "action-resource-b", true));

        assertEquals(ActionExecutionStatus.ACCEPTED, resourceB.status());
        waitForTerminal(first, runB);
        assertEquals(2, first.verifierCalls().get());
        assertFalse(first.verifications()
                .find(runB.tenantId(), runB.verificationRunId())
                .orElseThrow()
                .resultManifestRef()
                .equals(first.verifications()
                        .find(runA.tenantId(), runA.verificationRunId())
                        .orElseThrow()
                        .resultManifestRef()));
    }

    @Test
    void durablePrepareRejectsWrongStoredInputDuplicateKeyAndResourceBinding() {
        assertPrepareOwnerRejected(
                "verification-action-wrong-input",
                "candidate-wrong-input",
                owner -> owner.toBuilder()
                        .input(new VerificationRunInput(
                                ownerInput(owner).verificationRunId(),
                                ownerInput(owner).candidateId(),
                                ownerInput(owner).expectedVerificationRunVersion() + 1).toMap())
                        .build());
        assertPrepareOwnerRejected(
                "verification-action-wrong-key",
                "candidate-wrong-key",
                owner -> owner.toBuilder().duplicateKey("verification:wrong-owner").build());
        assertPrepareOwnerRejected(
                "verification-action-wrong-resource",
                "candidate-wrong-resource",
                owner -> owner.toBuilder()
                        .contextMetadata(Map.of(
                                "resource.type", VerificationRunAction.RESOURCE_TYPE,
                                "resource.id", "different-candidate"))
                        .build());
    }

    @Test
    void prepareRechecksTheLockedSessionCandidateHashAndCreatesNoStaleIntent() throws Exception {
        Fixture fixture = fixture("verification-action-stale-hash", "candidate-stale-hash");
        VerificationRun run = fixture.run();
        String actionRunId = "action-stale-hash";
        String attemptToken = "attempt-stale-hash";
        storeActionOwner(fixture, runningActionOwner(fixture, run, actionRunId, attemptToken));
        var transaction = new JdbcVerificationDispatchTransaction(fixture.dataSource());
        VerificationRunInput input = ownerInput(
                new JdbcRunStore(fixture.dataSource(), new com.fasterxml.jackson.databind.ObjectMapper())
                        .find(actionRunId).orElseThrow());
        try (Connection mutation = fixture.dataSource().getConnection();
                var competing = Executors.newSingleThreadExecutor()) {
            mutation.setAutoCommit(false);
            try (var update = mutation.prepareStatement("""
                    UPDATE factory_build_session
                    SET current_candidate_hash = ?, version = version + 1
                    WHERE tenant_id = ? AND build_session_id = ?
                    """)) {
                update.setString(1, hash("different-source").sha256());
                update.setString(2, run.tenantId().value());
                update.setString(3, run.buildSessionId().value());
                assertEquals(1, update.executeUpdate());
            }
            CountDownLatch prepareEntered = new CountDownLatch(1);
            var prepare = competing.submit(() -> {
                prepareEntered.countDown();
                return transaction.prepare(
                        run.tenantId(), input, actionRunId,
                        VerificationAttemptTokens.hash(attemptToken), NOW);
            });
            assertTrue(prepareEntered.await(1, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertFalse(prepare.isDone());
            mutation.commit();
            var failure = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> prepare.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
        }
        assertTrue(new JdbcVerificationDispatchIntentRepository(fixture.dataSource())
                .find(VerificationDispatchOperationIds.derive(input)).isEmpty());
        assertEquals(0, fixture.verifierCalls().get());
    }

    @Test
    void claimStartBoundarySerializesCancellationWinnerWithoutSandboxEffect() throws Exception {
        Fixture fixture = fixture("verification-action-cancel-before-claim", "candidate-cancel-before-claim");
        VerificationRun run = fixture.run();
        String actionRunId = "action-cancel-before-claim";
        String attemptToken = "attempt-cancel-before-claim";
        ActionRun owner = runningActionOwner(fixture, run, actionRunId, attemptToken);
        storeActionOwner(fixture, owner);
        VerificationRunInput input = ownerInput(owner);
        VerificationDispatchIntent prepared = new JdbcVerificationDispatchTransaction(fixture.dataSource())
                .prepare(run.tenantId(), input, actionRunId,
                        VerificationAttemptTokens.hash(attemptToken), NOW);
        var intents = new JdbcVerificationDispatchIntentRepository(fixture.dataSource());
        VerificationDispatchIntent blocked;
        try (Connection cancellation = fixture.dataSource().getConnection();
                var competing = Executors.newSingleThreadExecutor()) {
            cancellation.setAutoCommit(false);
            try (var update = cancellation.prepareStatement("""
                    UPDATE factory_build_session
                    SET cancellation_requested_at = ?, version = version + 1
                    WHERE tenant_id = ? AND build_session_id = ?
                    """)) {
                update.setObject(1, java.time.OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
                update.setString(2, run.tenantId().value());
                update.setString(3, run.buildSessionId().value());
                assertEquals(1, update.executeUpdate());
            }
            CountDownLatch claimEntered = new CountDownLatch(1);
            var claim = competing.submit(() -> {
                claimEntered.countDown();
                return intents.claimNext(
                        NOW.plusNanos(1), Duration.ofMinutes(30), "cancel-boundary-claim").orElseThrow();
            });
            assertTrue(claimEntered.await(1, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertFalse(claim.isDone());
            cancellation.commit();
            blocked = claim.get(5, TimeUnit.SECONDS);
        }

        assertEquals(VerificationDispatchIntentStatus.ORPHANED, blocked.status());
        assertEquals("VERIFICATION_DISPATCH_NO_LONGER_LAUNCHABLE", blocked.lastCode().orElseThrow());
        assertEquals(prepared.operationId(), blocked.operationId());
        assertEquals(0, fixture.verifierCalls().get());
    }

    @Test
    void terminalDuplicateOwnerWithoutIntentIsFoundAfterAtomicPrepareRollback() throws Exception {
        Fixture fixture = fixture(
                "verification-action-prepare-rollback",
                "candidate-prepare-rollback");
        try (Connection connection = fixture.dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE factory_verification_dispatch_intent
                    ADD CONSTRAINT ck_test_reject_verification_pending CHECK (status <> 'PENDING')
                    """);
        }
        DefaultActionRuntime runtime = runtime(fixture, duplicatePolicy(fixture));
        VerificationRun requested = fixture.run();

        ActionExecutionResult failed = runtime.handle(
                proposal(fixture, requested, "proposal-prepare-rollback"),
                context(fixture, "action-prepare-rollback", true));

        assertEquals(ActionExecutionStatus.FAILED, failed.status());
        assertEquals(VerificationRunStatus.REQUESTED,
                fixture.verifications().find(requested.tenantId(), requested.verificationRunId())
                        .orElseThrow().status());
        assertTrue(new JdbcVerificationDispatchIntentRepository(fixture.dataSource())
                .findLatest(requested.tenantId(), requested.verificationRunId()).isEmpty());
        assertEquals(0, fixture.verifierCalls().get());
        var evidenceOwner = new ActionRuntimeVerificationEvidenceOwner(
                new JdbcVerificationDispatchIntentRepository(fixture.dataSource()),
                new JdbcRunStore(fixture.dataSource(), new com.fasterxml.jackson.databind.ObjectMapper()),
                fixture.candidates(),
                new JdbcVerificationActionDuplicateOwnerLookup(fixture.dataSource()));
        assertEquals(
                VerificationActionEvidenceOwner.Status.INVALID_TERMINAL,
                evidenceOwner.assess(requested).status());

        ActionExecutionResult replay = runtime(fixture, duplicatePolicy(fixture)).handle(
                proposal(fixture, requested, "proposal-prepare-rollback-replay"),
                context(fixture, "action-prepare-rollback-replay", true));
        assertEquals(ActionExecutionStatus.FAILED, replay.status());
        assertEquals(0, fixture.verifierCalls().get());
    }

    @Test
    void maximumPracticalDomainIdsPersistAFixedLengthActionOperationId() throws Exception {
        String candidateId = "c".repeat(110);
        Fixture fixture = fixture("verification-action-long-operation", candidateId);
        VerificationRun run = fixture.run();
        String actionRunId = "action-long-operation";

        ActionExecutionResult accepted = runtime(fixture, duplicatePolicy(fixture)).handle(
                proposal(fixture, run, "proposal-long-operation"),
                context(fixture, actionRunId, true));
        assertEquals(ActionExecutionStatus.ACCEPTED, accepted.status());
        waitForTerminal(fixture);

        ActionRun owner = waitForActionTerminal(fixture, actionRunId);
        assertEquals(77, owner.externalOperationId().length());
        assertEquals(
                VerificationDispatchOperationIds.derive(new VerificationRunInput(
                        run.verificationRunId(), run.candidateId(), 0)),
                owner.externalOperationId());
        VerificationRun terminal = fixture.verifications()
                .find(run.tenantId(), run.verificationRunId()).orElseThrow();
        assertEquals(
                VerificationActionEvidenceOwner.Status.CANONICAL_SUCCEEDED,
                new ActionRuntimeVerificationEvidenceOwner(
                                new JdbcVerificationDispatchIntentRepository(fixture.dataSource()),
                                new JdbcRunStore(
                                        fixture.dataSource(),
                                        new com.fasterxml.jackson.databind.ObjectMapper()),
                                fixture.candidates(),
                                new JdbcVerificationActionDuplicateOwnerLookup(fixture.dataSource()))
                        .assess(terminal).status());
        assertEquals(1, fixture.verifierCalls().get());
    }

    private static DefaultActionRuntime runtime(Fixture fixture, DuplicateActionPolicy duplicatePolicy) {
        return runtime(fixture, duplicatePolicy, fixture.executor());
    }

    private static void assertPrepareOwnerRejected(
            String fixtureSuffix,
            String candidateSuffix,
            UnaryOperator<ActionRun> corruptOwner) {
        Fixture fixture = fixture(fixtureSuffix, candidateSuffix);
        VerificationRun run = fixture.run();
        String actionRunId = "action-" + candidateSuffix;
        String attemptToken = "attempt-" + candidateSuffix;
        ActionRun valid = runningActionOwner(fixture, run, actionRunId, attemptToken);
        ActionRun corrupted = corruptOwner.apply(valid);
        storeActionOwner(fixture, corrupted);
        VerificationRunInput expected = new VerificationRunInput(
                run.verificationRunId(), run.candidateId(), run.version());
        var transaction = new JdbcVerificationDispatchTransaction(fixture.dataSource());

        assertThrows(IllegalStateException.class, () -> transaction.prepare(
                run.tenantId(), expected, actionRunId,
                VerificationAttemptTokens.hash(attemptToken), NOW));
        assertTrue(new JdbcVerificationDispatchIntentRepository(fixture.dataSource())
                .find(VerificationDispatchOperationIds.derive(expected)).isEmpty());
        assertEquals(0, fixture.verifierCalls().get());
    }

    private static ActionRun runningActionOwner(
            Fixture fixture, VerificationRun run, String actionRunId, String attemptToken) {
        ActionRun requested = ActionRun.requested(
                proposal(fixture, run, "proposal-" + actionRunId),
                context(fixture, actionRunId, true));
        return requested.toBuilder()
                .version(requested.version() + 1)
                .status(ActionRunStatus.RUNNING)
                .currentStage("EXECUTE")
                .attemptToken(attemptToken)
                .updatedAt(requested.updatedAt().plusNanos(1))
                .build();
    }

    private static void storeActionOwner(Fixture fixture, ActionRun owner) {
        new JdbcRunStore(fixture.dataSource(), new com.fasterxml.jackson.databind.ObjectMapper()).create(owner);
    }

    private static VerificationRunInput ownerInput(ActionRun owner) {
        return VerificationRunInput.from(owner.input());
    }

    private static DefaultActionRuntime runtime(
            Fixture fixture, DuplicateActionPolicy duplicatePolicy, Executor executorLane) {
        var runStore = new JdbcRunStore(
                fixture.dataSource(), new com.fasterxml.jackson.databind.ObjectMapper());
        var runtimeRef = new AtomicReference<DefaultActionRuntime>();
        var delegate = new JdbcVerificationDispatchTransaction(
                fixture.dataSource(), new com.fasterxml.jackson.databind.ObjectMapper());
        VerificationDispatchTransaction schedulingTransaction = new VerificationDispatchTransaction() {
            @Override
            public VerificationDispatchIntent prepare(
                    io.github.flowerjvm.factory.contracts.ids.TenantId tenantId,
                    VerificationRunInput input,
                    String actionRunId,
                    String attemptTokenHash,
                    Instant preparedAt) {
                VerificationDispatchIntent intent = delegate.prepare(
                        tenantId, input, actionRunId, attemptTokenHash, preparedAt);
                executorLane.execute(() -> {
                    DefaultActionRuntime runtime;
                    long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while ((runtime = runtimeRef.get()) == null
                            || runStore.find(actionRunId)
                                    .filter(run -> run.status()
                                            == io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus.WAITING_EXTERNAL)
                                    .isEmpty()) {
                        if (System.nanoTime() >= waitUntil) {
                            throw new IllegalStateException("ActionRun did not reach WAITING_EXTERNAL");
                        }
                        Thread.onSpinWait();
                    }
                    new VerificationDispatchRunner(
                            new JdbcVerificationDispatchIntentRepository(fixture.dataSource()),
                            fixture.verifications(),
                            fixture.candidates(),
                            fixture.executionService(),
                            runtime,
                            runStore,
                            Clock.fixed(NOW, ZoneOffset.UTC),
                            java.time.Duration.ofMinutes(30),
                            java.time.Duration.ofMillis(1))
                            .tickOnce();
                });
                return intent;
            }

            @Override
            public Optional<VerificationDispatchIntent> findExact(
                    io.github.flowerjvm.factory.contracts.ids.TenantId tenantId,
                    VerificationRunInput input,
                    String actionRunId,
                    String attemptTokenHash) {
                return delegate.findExact(tenantId, input, actionRunId, attemptTokenHash);
            }
        };
        var executor = new VerificationRunActionExecutor(
                schedulingTransaction, Clock.fixed(NOW, ZoneOffset.UTC));
        var runtime = new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(executor)),
                new VerificationRunActionValidator(),
                new VerificationRunPolicyGate(fixture.verifications(), fixture.candidates()),
                ApprovalGate.unsupported(),
                duplicatePolicy,
                AuditSink.noop(),
                TraceSink.noop(),
                runStore,
                new VerificationRunPreExecutionGuard(
                        fixture.buildSessions(),
                        fixture.candidates(),
                        fixture.verifications(),
                        FIXTURE_HASH,
                        Clock.fixed(NOW, ZoneOffset.UTC)));
        runtimeRef.set(runtime);
        return runtime;
    }

    private static JdbcDuplicateActionPolicy duplicatePolicy(Fixture fixture) {
        return JdbcDuplicateActionPolicy.create(
                fixture.dataSource(),
                new VerificationRunVisibilityScopeResolver(fixture.verifications(), fixture.candidates()));
    }

    private static ActionProposal proposal(Fixture fixture, VerificationRun run, String proposalId) {
        CandidateVersion candidate = fixture.candidates()
                .find(run.tenantId(), run.candidateId())
                .orElseThrow();
        return ActionProposal.builder(VerificationRunAction.ACTION_ID)
                .proposalId(proposalId)
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("factory-verifier")
                .input(new VerificationRunInput(
                                run.verificationRunId(), run.candidateId(), run.version())
                        .toMap())
                .idempotencyKey(VerificationRunIdempotencyKeys.derive(run, candidate))
                .build();
    }

    private static ExecutionContext context(Fixture fixture, String runId, boolean permitted) {
        return new ExecutionContext(
                fixture.run().tenantId().value(),
                permitted ? "factory-verifier" : "denied-principal",
                runId,
                "trace-" + runId,
                Map.of(
                        "actor.permissions",
                        permitted ? Set.of(VerificationRunAction.PERMISSION) : Set.of(),
                        "resource.type",
                        VerificationRunAction.RESOURCE_TYPE,
                        "resource.id",
                        fixture.currentCandidateId().value()));
    }

    private static Fixture fixture(String suffix, String candidateSuffix) {
        return fixture(FactoryDatabaseMigrationsTest.h2(suffix), suffix, candidateSuffix);
    }

    private static Fixture fixture(DataSource dataSource, String suffix, String candidateSuffix) {
        FactoryDatabaseMigrations.migrate(dataSource);
        BuildSession session = verifyingSession(
                suffix, new CandidateId(candidateSuffix), hash("source-" + candidateSuffix));
        new JdbcBuildSessionRepository(dataSource).create(session);
        Fixture fixture = new Fixture(
                dataSource,
                new JdbcBuildSessionRepository(dataSource),
                new JdbcCandidateVersionRepository(dataSource),
                new JdbcVerificationRunRepository(dataSource),
                new JdbcArtifactStore(dataSource, Clock.fixed(NOW, ZoneOffset.UTC)),
                Executors.newFixedThreadPool(2, runnable -> {
                    Thread thread = new Thread(runnable, "verification-action-acceptance");
                    thread.setDaemon(true);
                    return thread;
                }),
                new AtomicInteger(),
                session,
                null,
                null,
                null);
        CandidateVersion candidate = persistCandidate(fixture, candidateSuffix);
        VerificationRun run = requested(fixture, candidate, "verification-" + candidateSuffix);
        fixture.verifications().create(run);
        return fixture.withCandidateAndRun(candidate, run);
    }

    private static CandidateVersion persistCandidate(Fixture fixture, String suffix) {
        return persistCandidate(
                fixture,
                suffix,
                new ArtifactReference("artifact:dependency:" + suffix),
                hash("dependency-" + suffix));
    }

    private static CandidateVersion persistCandidate(
            Fixture fixture,
            String suffix,
            ArtifactReference dependencyLockRef,
            ContentHash dependencyLockHash) {
        CandidateVersion candidate = new CandidateVersion(
                new CandidateId(suffix),
                fixture.session().tenantId(),
                fixture.session().buildSessionId(),
                Optional.empty(),
                new ArtifactReference("artifact:source:" + suffix),
                hash("source-" + suffix),
                dependencyLockRef,
                dependencyLockHash,
                new ArtifactReference("artifact:toolchain:" + suffix),
                hash("toolchain-" + suffix),
                io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus.GENERATED,
                new io.github.flowerjvm.factory.contracts.ids.WorkOrderId("work-" + suffix),
                NOW.minusSeconds(20));
        // Candidate FK owns a WorkOrder. Persist the generic immutable order before the candidate.
        new JdbcWorkOrderRepository(fixture.dataSource()).create(new io.github.flowerjvm.factory.contracts.worker.WorkOrder(
                candidate.createdByWorkOrderId(),
                candidate.tenantId(),
                candidate.buildSessionId(),
                "generate-candidate",
                "Generate verification fixture",
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new ArtifactReference("artifact:instruction:" + suffix),
                hash("instruction-" + suffix),
                new ArtifactReference("artifact:input:" + suffix),
                hash("input-" + suffix),
                "workspace:" + suffix,
                List.of("/workspace/input"),
                List.of("/workspace/output"),
                Set.of(),
                "pack-candidate",
                "1",
                new ArtifactReference("artifact:policy:" + suffix),
                NOW.plusSeconds(1800),
                1,
                "logical-" + suffix,
                io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType.SYSTEM,
                "factory",
                NOW.minusSeconds(30)));
        fixture.candidates().create(candidate);
        byte[] sourceManifest = "{}".getBytes(StandardCharsets.UTF_8);
        fixture.artifacts().store(new Artifact(
                candidate.tenantId(),
                candidate.sourceManifestRef(),
                sha256(sourceManifest),
                "application/json",
                sourceManifest));
        if (fixture.artifacts().find(candidate.tenantId(), candidate.dependencyLockRef()).isEmpty()) {
            fixture.artifacts().store(new Artifact(
                    candidate.tenantId(), candidate.dependencyLockRef(), candidate.dependencyLockHash(),
                    "application/json", ("dependency-" + suffix).getBytes(StandardCharsets.UTF_8)));
        }
        fixture.artifacts().store(new Artifact(
                candidate.tenantId(), candidate.toolchainLockRef(), candidate.toolchainLockHash(),
                "application/json", ("toolchain-" + suffix).getBytes(StandardCharsets.UTF_8)));
        fixture.currentCandidateIdHolder().set(candidate.candidateId());
        return candidate;
    }

    private static VerificationRun requested(Fixture fixture, CandidateVersion candidate, String id) {
        return new VerificationRun(
                new io.github.flowerjvm.factory.contracts.ids.VerificationRunId(id),
                candidate.tenantId(),
                candidate.buildSessionId(),
                candidate.candidateId(),
                candidate.sourceHash(),
                ActionBackedVerificationRunLauncher.GATE_PROFILE,
                candidate.toolchainLockHash(),
                FIXTURE_HASH,
                VerificationRunStatus.REQUESTED,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                0,
                NOW.minusSeconds(1),
                NOW.minusSeconds(1));
    }

    private static void rebindSession(Fixture fixture, CandidateVersion candidate) {
        BuildSession current = fixture.buildSessions()
                .find(fixture.session().tenantId(), fixture.session().buildSessionId())
                .orElseThrow();
        BuildSession rebound = new BuildSession(
                current.buildSessionId(), current.tenantId(), current.projectId(), current.productLineId(),
                current.requestIdempotencyKey(),
                current.createdBy(), current.status(), current.currentPhase(), current.requirementsArtifactRef(),
                current.requirementsHash(), current.selectedManagerWorkerBinding(), current.selectedCodingWorkerBinding(),
                current.currentBlueprintRef(), Optional.of(candidate.candidateId()), Optional.of(candidate.sourceHash()),
                current.currentCertificationId(), current.repairRound(), current.maxRepairRounds(), current.startedAt(),
                current.deadlineAt(), current.cancellationRequestedAt(), current.terminalCode(), current.terminalMessage(),
                current.version() + 1, current.createdAt(), current.updatedAt().plusNanos(1));
        assertTrue(fixture.buildSessions().compareAndSet(current, rebound));
    }

    private static BuildSession verifyingSession(
            String suffix, CandidateId candidateId, ContentHash candidateHash) {
        BuildSession template = PersistenceFixtures.buildSession(suffix);
        return new BuildSession(
                template.buildSessionId(), template.tenantId(), template.projectId(), template.productLineId(),
                template.requestIdempotencyKey(),
                template.createdBy(), BuildSessionStatus.VERIFYING, BuildSessionPhase.TEST,
                template.requirementsArtifactRef(), template.requirementsHash(), template.selectedManagerWorkerBinding(),
                template.selectedCodingWorkerBinding(), template.currentBlueprintRef(), Optional.of(candidateId),
                Optional.of(candidateHash),
                template.currentCertificationId(), template.repairRound(), template.maxRepairRounds(),
                NOW.minusSeconds(60), NOW.plusSeconds(3600), Optional.empty(), Optional.empty(), Optional.empty(),
                0, NOW.minusSeconds(60), NOW.minusSeconds(1));
    }

    private static void waitForTerminal(Fixture fixture) throws Exception {
        waitForTerminal(fixture, fixture.run());
    }

    private static void waitForTerminal(Fixture fixture, VerificationRun expected) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (fixture.verifications().find(expected.tenantId(), expected.verificationRunId())
                    .filter(run -> run.status().isTerminal()).isPresent()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("verification did not reach a terminal state");
    }

    private static ActionRun waitForActionTerminal(Fixture fixture, String actionRunId) throws Exception {
        var store = new JdbcRunStore(
                fixture.dataSource(), new com.fasterxml.jackson.databind.ObjectMapper());
        for (int i = 0; i < 100; i++) {
            var terminal = store.find(actionRunId).filter(run -> run.status().isTerminal());
            if (terminal.isPresent()) {
                return terminal.orElseThrow();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("verification Action did not reach a terminal state");
    }

    private static ContentHash hash(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ContentHash sha256(byte[] value) {
        try {
            return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class ObservedDuplicatePolicy implements DuplicateActionPolicy {
        private final DuplicateActionPolicy delegate;
        private final AtomicInteger accepts;

        private ObservedDuplicatePolicy(
                DuplicateActionPolicy delegate, AtomicInteger accepts) {
            this.delegate = delegate;
            this.accepts = accepts;
        }

        @Override
        public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
            DuplicateActionDecision decision = delegate.reserve(proposal, context);
            if (decision.type() == DuplicateActionDecisionType.ACCEPT) {
                accepts.incrementAndGet();
            }
            return decision;
        }

        @Override
        public void complete(ActionProposal proposal, ExecutionContext context, ActionExecutionResult result) {
            delegate.complete(proposal, context, result);
        }

        @Override
        public void release(ActionProposal proposal, ExecutionContext context, Throwable cause) {
            delegate.release(proposal, context, cause);
        }
    }

    /** Meets replay reserve and original completion immediately before their JDBC transitions. */
    private static final class TransitionBarrierDuplicatePolicy implements DuplicateActionPolicy {
        private final DuplicateActionPolicy delegate;
        private final CyclicBarrier barrier = new CyclicBarrier(2);
        private final AtomicInteger transitionEntrants = new AtomicInteger();
        private volatile boolean enabled;

        private TransitionBarrierDuplicatePolicy(DuplicateActionPolicy delegate) {
            this.delegate = delegate;
        }

        void enable() {
            enabled = true;
        }

        int transitionEntrants() {
            return transitionEntrants.get();
        }

        @Override
        public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
            awaitIfEnabled();
            return delegate.reserve(proposal, context);
        }

        @Override
        public void complete(ActionProposal proposal, ExecutionContext context, ActionExecutionResult result) {
            awaitIfEnabled();
            delegate.complete(proposal, context, result);
        }

        @Override
        public void release(ActionProposal proposal, ExecutionContext context, Throwable cause) {
            delegate.release(proposal, context, cause);
        }

        private void awaitIfEnabled() {
            if (!enabled) {
                return;
            }
            transitionEntrants.incrementAndGet();
            try {
                barrier.await(5, TimeUnit.SECONDS);
                if (transitionEntrants.get() >= 2) {
                    enabled = false;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("duplicate transition barrier interrupted", exception);
            } catch (Exception exception) {
                throw new IllegalStateException("duplicate transition barrier failed", exception);
            }
        }
    }

    private record Fixture(
            DataSource dataSource,
            JdbcBuildSessionRepository buildSessions,
            JdbcCandidateVersionRepository candidates,
            JdbcVerificationRunRepository verifications,
            JdbcArtifactStore artifacts,
            java.util.concurrent.ExecutorService executor,
            AtomicInteger verifierCalls,
            BuildSession session,
            CandidateVersion candidate,
            VerificationRun run,
            java.util.concurrent.atomic.AtomicReference<CandidateId> currentCandidateIdHolder) {
        private Fixture {
            if (currentCandidateIdHolder == null) {
                currentCandidateIdHolder = new java.util.concurrent.atomic.AtomicReference<>();
            }
        }

        private Fixture withCandidateAndRun(CandidateVersion newCandidate, VerificationRun newRun) {
            return new Fixture(dataSource, buildSessions, candidates, verifications, artifacts, executor,
                    verifierCalls, session, newCandidate, newRun, currentCandidateIdHolder);
        }

        VerificationExecutionService executionService() {
            return new VerificationExecutionService(
                    verifications, candidates, artifacts, request -> {
                        verifierCalls.incrementAndGet();
                        byte[] result = ("result:" + request.verificationRunId().value()).getBytes(StandardCharsets.UTF_8);
                        ContentHash resultHash = sha256(result);
                        ArtifactReference resultRef = new ArtifactReference(
                                "artifact:verification-result:" + request.verificationRunId().value());
                        artifacts.store(new Artifact(request.tenantId(), resultRef, resultHash, "application/json", result));
                        return new VerificationResult(VerificationStatus.PASSED, VerificationDisposition.REVIEW_ELIGIBLE,
                                List.of(VerificationStableCodes.VERIFIED), resultRef, resultHash, List.of(resultRef));
                    }, Clock.fixed(NOW, ZoneOffset.UTC), new ArtifactReference("artifact:fixture-set"));
        }

        CandidateId currentCandidateId() {
            return currentCandidateIdHolder.get();
        }
    }
}
