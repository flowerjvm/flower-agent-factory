package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.candidate.*;
import io.github.flowerjvm.factory.application.decision.*;
import io.github.flowerjvm.factory.application.verification.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.factory.contracts.verification.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcAgentPackReleaseReviewTransactionTest {
    private static final Instant NOW = PersistenceFixtures.NOW.plusSeconds(60);
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final AgentPackReleaseReviewPolicy POLICY = new AgentPackReleaseReviewPolicy(WINDOW);

    @Test
    void concurrentOpenAndRestartConvergeToOnePointWithoutAnyDecisionOrSessionMutation() throws Exception {
        var f = new Fixture();
        var barrier = new CyclicBarrier(2);
        List<AgentPackReleaseReviewResult> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { barrier.await(); return f.open(f.transaction()); });
            var second = executor.submit(() -> { barrier.await(); return f.open(f.transaction()); });
            results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, results.stream().filter(r -> r.disposition() == AgentPackReleaseReviewResult.Disposition.CREATED).count());
        assertEquals(1, results.stream().filter(r -> r.disposition() == AgentPackReleaseReviewResult.Disposition.EXISTING_EXACT).count());
        assertEquals(results.getFirst().decisionPoint(), results.getLast().decisionPoint());
        var restarted = f.transaction();
        assertEquals(AgentPackReleaseReviewResult.Disposition.EXISTING_EXACT, f.open(restarted).disposition());
        assertEquals(DecisionPointStatus.OPEN, f.points.find(f.session.tenantId(), f.point.decisionPointId()).orElseThrow().status());
        assertEquals(f.session, f.sessions.find(f.session.tenantId(), f.session.buildSessionId()).orElseThrow());
        assertEquals(1, f.count("factory_decision_point"));
        assertEquals(0, f.count("factory_decision"));
    }

    @Test
    void cancellationAndStaleSessionVersionRejectAtomicOpen() throws Exception {
        var f = new Fixture();
        assertTrue(f.sessions.compareAndSet(f.session, f.session.requestCancellation(NOW)));
        assertThrows(IllegalArgumentException.class, () -> f.open(f.transaction()));
        assertEquals(0, f.count("factory_decision_point"));
        var g = new Fixture();
        var versionFence = copy(g.session, "version", g.session.version() + 1, "updatedAt", NOW.plusNanos(1000));
        assertTrue(g.sessions.compareAndSet(g.session, versionFence));
        assertThrows(IllegalArgumentException.class, () -> g.open(g.transaction()));
        assertEquals(0, g.count("factory_decision_point"));
    }

    @Test
    void cancellationCommittedWhileOpenWaitsForSessionLockPreventsInsert() throws Exception {
        var f = new Fixture();
        try (var blocker = f.dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            f.sessions.find(blocker, f.session.tenantId(), f.session.buildSessionId(), true).orElseThrow();
            var started = new CountDownLatch(1);
            var pending = executor.submit(() -> { started.countDown(); return f.open(f.transaction()); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(f.sessions.compareAndSet(blocker, f.session, f.session.requestCancellation(NOW)));
            blocker.commit();
            var failure = assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
        assertEquals(0, f.count("factory_decision_point"));
    }

    @Test
    void deadlineCrossedAfterSnapshotValidationFailsInsideTransaction() throws Exception {
        var f = new Fixture();
        var reads = new AtomicInteger();
        Clock advancing = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return reads.getAndIncrement() == 0 ? NOW : f.session.deadlineAt(); }
        };
        var transaction = new JdbcAgentPackReleaseReviewTransaction(f.dataSource, new ObjectMapper(), advancing, WINDOW);
        assertThrows(IllegalArgumentException.class, () -> f.open(transaction));
        assertEquals(0, f.count("factory_decision_point"));
    }

    @Test
    void changedCurrentCandidateOrSourceSnapshotCannotUsePreviouslyValidatedAuthority() throws Exception {
        var f = new Fixture();
        var changedSession = copy(f.session, "currentCandidateId", Optional.of(new CandidateId("different")),
                "currentCandidateHash", Optional.of(PersistenceFixtures.HASH_B), "version", f.session.version() + 1);
        assertTrue(f.sessions.compareAndSet(f.session, changedSession));
        assertThrows(IllegalArgumentException.class, () -> f.open(f.transaction()));
        assertEquals(0, f.count("factory_decision_point"));
        var g = new Fixture();
        var forgedSource = copy(g.candidate, "sourceManifestRef", new ArtifactReference("artifact:changed-source"));
        assertThrows(IllegalArgumentException.class,
                () -> g.transaction().ensureOpen(g.session, forgedSource, g.verification, g.point));
        assertEquals(0, g.count("factory_decision_point"));
    }

    @Test
    void newerSelectedGateRunCannotReuseStaleVerificationSnapshotOrPriorQuestion() throws Exception {
        var f = new Fixture();
        var newer = copy(f.verification, "verificationRunId", new VerificationRunId("verify-newer"),
                "createdAt", f.verification.createdAt().plusNanos(1000));
        f.verifications.create(newer);
        assertThrows(IllegalArgumentException.class, () -> f.open(f.transaction()));
        assertEquals(0, f.count("factory_decision_point"));

        var g = new Fixture();
        g.open(g.transaction());
        var changedVerification = copy(g.verification, "verificationRunId", new VerificationRunId("verify-later"),
                "createdAt", g.verification.createdAt().plusNanos(1000));
        g.verifications.create(changedVerification);
        byte[] question = POLICY.questionBytes(g.session, g.candidate, changedVerification, PersistenceFixtures.HASH_A);
        DecisionPoint requested = POLICY.requestedPoint(g.session, g.candidate, POLICY.questionReference(question), NOW);
        assertThrows(IllegalArgumentException.class,
                () -> g.transaction().ensureOpen(g.session, g.candidate, changedVerification, requested));
        assertEquals(g.point, g.points.find(g.session.tenantId(), g.point.decisionPointId()).orElseThrow());
        assertEquals(1, g.count("factory_decision_point"));
    }

    @Test
    void conflictingPriorSubjectOrPolicyNeverCreatesASecondReview() throws Exception {
        var f = new Fixture();
        f.points.create(copy(f.point, "decisionPointId", new DecisionPointId("historical-other-point")));
        assertThrows(IllegalArgumentException.class, () -> f.open(f.transaction()));
        assertEquals(1, f.count("factory_decision_point"));
        var g = new Fixture();
        g.points.create(copy(g.point, "optionsSchemaId", "untrusted-options"));
        assertThrows(IllegalArgumentException.class, () -> g.open(g.transaction()));
        assertEquals(1, g.count("factory_decision_point"));
    }

    @Test
    void matchingStoredTerminalDecisionIsObservedNeverReopenedOrReplaced() throws Exception {
        var f = new Fixture();
        f.open(f.transaction());
        Decision humanDecision = new Decision(new DecisionId("human-decision"), f.session.tenantId(),
                f.point.decisionPointId(), "human-key", DecisionOutcome.APPROVE,
                Optional.empty(), Optional.empty(), "human-reviewer",
                new ArtifactReference("artifact:human-authority"), f.point.subjectHash(),
                NOW.plusSeconds(1));
        // Use the existing recording transaction and trusted authority; review opening itself
        // has no DecisionRepository dependency and cannot synthesize this human record.
        var decided = f.point.decide(humanDecision, humanDecision.createdAt());
        var transaction = new JdbcDecisionRecordingTransaction(f.dataSource, new ObjectMapper());
        transaction.record(f.session, f.point, humanDecision, decided);
        var result = f.open(new JdbcAgentPackReleaseReviewTransaction(f.dataSource, new ObjectMapper(),
                Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC), WINDOW));
        assertEquals(DecisionPointStatus.APPROVED, result.decisionPoint().status());
        assertEquals(humanDecision.decisionId(), result.decisionPoint().terminalDecisionId().orElseThrow());
        assertEquals(1, f.count("factory_decision"));
        assertEquals(1, f.count("factory_decision_point"));
    }

    private static final class Fixture {
        final DataSource dataSource = dataSource();
        final JdbcBuildSessionRepository sessions = new JdbcBuildSessionRepository(dataSource);
        final JdbcCandidateVersionRepository candidates = new JdbcCandidateVersionRepository(dataSource);
        final JdbcVerificationRunRepository verifications = new JdbcVerificationRunRepository(dataSource);
        final JdbcDecisionPointRepository points = new JdbcDecisionPointRepository(dataSource);
        final BuildSession session;
        final CandidateVersion candidate;
        final VerificationRun verification;
        final DecisionPoint point;

        Fixture() {
            FactoryDatabaseMigrations.migrate(dataSource);
            var original = PersistenceFixtures.buildSession("review-open");
            var candidateId = new CandidateId("candidate-review-open");
            session = copy(original, "status", BuildSessionStatus.WAITING_RELEASE_REVIEW,
                    "currentPhase", BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                    "currentCandidateId", Optional.of(candidateId),
                    "currentCandidateHash", Optional.of(PersistenceFixtures.HASH_A));
            sessions.create(session);
            var order = PersistenceFixtures.workOrder(session, "review-open");
            new JdbcWorkOrderRepository(dataSource, new ObjectMapper()).create(order);
            candidate = new CandidateVersion(candidateId, session.tenantId(), session.buildSessionId(), Optional.empty(),
                    new ArtifactReference("artifact:source-review-open"), PersistenceFixtures.HASH_A,
                    new ArtifactReference("artifact:dependencies-review-open"), PersistenceFixtures.HASH_A,
                    new ArtifactReference("artifact:toolchain-review-open"), PersistenceFixtures.HASH_B,
                    CandidateVersionStatus.GENERATED, order.workOrderId(), PersistenceFixtures.NOW);
            candidates.create(candidate);
            verification = new VerificationRun(new VerificationRunId("verify-review-open"), session.tenantId(),
                    session.buildSessionId(), candidateId, candidate.sourceHash(), ActionBackedVerificationRunLauncher.GATE_PROFILE,
                    candidate.toolchainLockHash(), PersistenceFixtures.HASH_A, VerificationRunStatus.PASSED,
                    Optional.of(new ArtifactReference("artifact:verified-review-open")), Optional.of(PersistenceFixtures.HASH_B),
                    Optional.of(VerificationStableCodes.VERIFIED), Optional.of(VerificationDisposition.REVIEW_ELIGIBLE),
                    Optional.of(NOW.minusSeconds(5)), Optional.of(NOW.minusSeconds(1)), 2, NOW.minusSeconds(10), NOW.minusSeconds(1));
            verifications.create(verification);
            byte[] question = POLICY.questionBytes(session, candidate, verification, PersistenceFixtures.HASH_A);
            point = POLICY.requestedPoint(session, candidate, POLICY.questionReference(question), NOW);
        }

        JdbcAgentPackReleaseReviewTransaction transaction() {
            return new JdbcAgentPackReleaseReviewTransaction(dataSource, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), WINDOW);
        }
        AgentPackReleaseReviewResult open(JdbcAgentPackReleaseReviewTransaction transaction) {
            return transaction.ensureOpen(session, candidate, verification, point);
        }
        int count(String trustedTable) throws Exception {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT COUNT(*) FROM " + trustedTable)) {
                assertTrue(result.next()); return result.getInt(1);
            }
        }
    }

    private static DataSource dataSource() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        source.setUser("sa");
        source.setPassword("");
        return source;
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
