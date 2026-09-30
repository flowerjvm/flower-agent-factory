package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.decision.Decision;
import io.github.flowerjvm.factory.application.decision.DecisionOutcome;
import io.github.flowerjvm.factory.application.decision.DecisionRecordingDisposition;
import io.github.flowerjvm.factory.application.decision.DecisionRecordingService;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.application.decision.DecisionRequestContext;
import io.github.flowerjvm.factory.application.decision.AgentPackReleaseReviewPolicy;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcDecisionRecordingTransactionTest {
    @Test
    void duplicateDecisionSignalInsertsAndTerminalizesExactlyOnce() throws Exception {
        DataSource dataSource = dataSource();
        FactoryDatabaseMigrations.migrate(dataSource);
        var mapper = new ObjectMapper();
        var sessions = new JdbcBuildSessionRepository(dataSource);
        var points = new JdbcDecisionPointRepository(dataSource, mapper);
        var decisions = new JdbcDecisionRepository(dataSource);
        var service = new DecisionRecordingService(
                sessions,
                points,
                decisions,
                new JdbcDecisionRecordingTransaction(dataSource, mapper));
        var session = releaseReviewSession(
                PersistenceFixtures.buildSession("decision-transaction"),
                "decision-transaction");
        var point = PersistenceFixtures.decisionPoint(session, "decision-transaction");
        var decision = PersistenceFixtures.decision(point, "decision-transaction");
        var retry = copy(decision, "decision-transaction-retry", decision.decision(), decision.createdAt());
        sessions.create(session);
        points.create(point);
        var barrier = new CyclicBarrier(2);
        var requestContext = authorizedContext(decision.createdAt());

        List<DecisionRecordingDisposition> dispositions;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await();
                return service.record(decision, requestContext);
            });
            var second = executor.submit(() -> {
                barrier.await();
                return service.record(retry, requestContext);
            });
            dispositions = List.of(
                    first.get(10, TimeUnit.SECONDS).disposition(),
                    second.get(10, TimeUnit.SECONDS).disposition());
        }

        assertTrue(dispositions.contains(DecisionRecordingDisposition.APPLIED));
        assertTrue(dispositions.contains(DecisionRecordingDisposition.DUPLICATE));
        assertEquals(1L, dispositions.stream().filter(DecisionRecordingDisposition.APPLIED::equals).count());
        var storedPoint = points.find(point.tenantId(), point.decisionPointId()).orElseThrow();
        var storedDecision = decisions.findByRequestIdempotencyKey(
                        decision.tenantId(), decision.decisionPointId(), decision.requestIdempotencyKey())
                .orElseThrow();
        assertEquals(DecisionPointStatus.APPROVED, storedPoint.status());
        assertEquals(storedDecision.decisionId(), storedPoint.terminalDecisionId().orElseThrow());
        assertTrue(storedDecision.decisionId().equals(decision.decisionId())
                || storedDecision.decisionId().equals(retry.decisionId()));
        assertEquals(new ArtifactReference("artifact:trusted-authority"), storedDecision.deciderAuthoritySnapshotRef());
    }

    @Test
    void transportRetryReturnsStoredDecisionEvenWithNewDecisionIdAndTimestamp() {
        var fixture = fixture("stored-retry");
        var first = fixture.service().record(fixture.decision(), authorizedContext(fixture.decision().createdAt()));
        var retry = copy(
                fixture.decision(),
                "stored-retry-new-id",
                fixture.decision().decision(),
                fixture.point().openedAt().minusSeconds(3600));

        var duplicate = fixture.service().record(
                retry,
                authorizedContext(fixture.decision().createdAt().plusSeconds(30)));

        assertEquals(DecisionRecordingDisposition.APPLIED, first.disposition());
        assertEquals(DecisionRecordingDisposition.DUPLICATE, duplicate.disposition());
        assertEquals(first.recordedDecision(), duplicate.recordedDecision());
        assertEquals(
                fixture.decision().createdAt(),
                duplicate.recordedDecision().orElseThrow().createdAt());
    }

    @Test
    void transportRetryReturnsCanonicalDecisionAfterFlowAdvancedTheSession() {
        var fixture = fixture("stored-retry-after-flow");
        var first = fixture.service().record(
                fixture.decision(), authorizedContext(fixture.decision().createdAt()));
        assertTrue(fixture.sessions().compareAndSet(
                fixture.session(), candidateReady(fixture.session())));
        var retry = copy(
                fixture.decision(),
                "stored-retry-after-flow-new-id",
                fixture.decision().decision(),
                fixture.decision().createdAt().plusSeconds(30));

        var duplicate = fixture.service().record(
                retry,
                authorizedContext(fixture.decision().createdAt().plusSeconds(30)));

        assertEquals(DecisionRecordingDisposition.APPLIED, first.disposition());
        assertEquals(DecisionRecordingDisposition.DUPLICATE, duplicate.disposition());
        assertEquals(first.recordedDecision(), duplicate.recordedDecision());
        assertEquals(
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                fixture.sessions().find(fixture.session().tenantId(), fixture.session().buildSessionId())
                        .orElseThrow().status());
    }

    @Test
    void sameRequestKeyWithDifferentPayloadConflictsWithoutDisclosingStoredDecision() {
        var fixture = fixture("payload-conflict");
        var first = fixture.service().record(
                fixture.decision(), authorizedContext(fixture.decision().createdAt()));
        var changed = copy(
                fixture.decision(),
                "payload-conflict-new-id",
                DecisionOutcome.REQUEST_CHANGES,
                fixture.decision().createdAt());

        var result = fixture.service().record(changed, authorizedContext(changed.createdAt()));

        assertEquals(DecisionRecordingDisposition.CONFLICT, result.disposition());
        assertTrue(result.recordedDecision().isEmpty());
        assertEquals(
                first.recordedDecision().orElseThrow(),
                fixture.decisions().findByRequestIdempotencyKey(
                                fixture.decision().tenantId(),
                                fixture.decision().decisionPointId(),
                                fixture.decision().requestIdempotencyKey())
                        .orElseThrow());
    }

    @Test
    void trustedCurrentTimePreventsBackdatedExpiryBypass() {
        var fixture = fixture("trusted-time");
        var backdated = copy(
                fixture.decision(),
                "trusted-time-backdated",
                fixture.decision().decision(),
                fixture.point().openedAt().plusSeconds(1));

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service().record(backdated, authorizedContext(fixture.point().dueAt())));

        assertEquals("DECISION_EXPIRED", failure.getMessage());
        assertTrue(fixture.decisions().find(
                        backdated.tenantId(), backdated.decisionId())
                .isEmpty());
        assertEquals(
                DecisionPointStatus.OPEN,
                fixture.points().find(backdated.tenantId(), backdated.decisionPointId()).orElseThrow().status());
    }

    @Test
    void authenticatedPrincipalAndRequiredPermissionsFailClosed() {
        var fixture = fixture("authorization");

        var wrongPrincipal = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service().record(
                        fixture.decision(),
                        new DecisionRequestContext(
                                fixture.decision().tenantId(),
                                fixture.decision().createdAt(),
                                "reviewer-b",
                                Set.of(AgentPackReleaseReviewPolicy.REQUIRED_PERMISSION),
                                new ArtifactReference("artifact:trusted-authority"))));
        var missingPermission = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service().record(
                        fixture.decision(),
                        new DecisionRequestContext(
                                fixture.decision().tenantId(),
                                fixture.decision().createdAt(),
                                fixture.decision().decidedBy(),
                                Set.of(),
                                new ArtifactReference("artifact:trusted-authority"))));

        assertEquals("DECISION_UNAUTHORIZED", wrongPrincipal.getMessage());
        assertEquals("DECISION_UNAUTHORIZED", missingPermission.getMessage());
        assertFalse(fixture.points()
                .find(fixture.point().tenantId(), fixture.point().decisionPointId())
                .orElseThrow()
                .status()
                .isTerminal());
        assertTrue(fixture.decisions()
                .findByRequestIdempotencyKey(
                        fixture.decision().tenantId(),
                        fixture.decision().decisionPointId(),
                        fixture.decision().requestIdempotencyKey())
                .isEmpty());
    }

    @Test
    void trustedTenantIsCheckedBeforeTenantScopedLookup() {
        var fixture = fixture("tenant-boundary");

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service().record(
                        fixture.decision(),
                        new DecisionRequestContext(
                                new TenantId("tenant-b"),
                                fixture.decision().createdAt(),
                                fixture.decision().decidedBy(),
                                Set.of(AgentPackReleaseReviewPolicy.REQUIRED_PERMISSION),
                                new ArtifactReference("artifact:trusted-authority"))));

        assertEquals("DECISION_UNAUTHORIZED", failure.getMessage());
        assertFalse(fixture.points()
                .find(fixture.point().tenantId(), fixture.point().decisionPointId())
                .orElseThrow()
                .status()
                .isTerminal());
    }

    @Test
    void changedCurrentCandidateMakesOpenedReviewStale() {
        var fixture = fixture("candidate-change");
        var changed = changedCandidate(fixture.session());
        assertTrue(fixture.sessions().compareAndSet(fixture.session(), changed));

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service().record(
                        fixture.decision(), authorizedContext(fixture.decision().createdAt())));

        assertEquals("DECISION_SUBJECT_CHANGED", failure.getMessage());
        assertFalse(fixture.points()
                .find(fixture.point().tenantId(), fixture.point().decisionPointId())
                .orElseThrow()
                .status()
                .isTerminal());
        assertTrue(fixture.decisions()
                .findByRequestIdempotencyKey(
                        fixture.decision().tenantId(),
                        fixture.decision().decisionPointId(),
                        fixture.decision().requestIdempotencyKey())
                .isEmpty());
    }

    private static Fixture fixture(String suffix) {
        DataSource dataSource = dataSource();
        FactoryDatabaseMigrations.migrate(dataSource);
        var mapper = new ObjectMapper();
        var sessions = new JdbcBuildSessionRepository(dataSource);
        var points = new JdbcDecisionPointRepository(dataSource, mapper);
        var decisions = new JdbcDecisionRepository(dataSource);
        var service = new DecisionRecordingService(
                sessions,
                points,
                decisions,
                new JdbcDecisionRecordingTransaction(dataSource, mapper));
        var session = releaseReviewSession(PersistenceFixtures.buildSession(suffix), suffix);
        var point = PersistenceFixtures.decisionPoint(session, suffix);
        var decision = PersistenceFixtures.decision(point, suffix);
        sessions.create(session);
        points.create(point);
        return new Fixture(sessions, points, decisions, service, session, point, decision);
    }

    private static DecisionRequestContext authorizedContext(Instant currentTime) {
        return new DecisionRequestContext(
                PersistenceFixtures.TENANT,
                currentTime,
                "reviewer-a",
                Set.of(AgentPackReleaseReviewPolicy.REQUIRED_PERMISSION),
                new ArtifactReference("artifact:trusted-authority"));
    }

    private static Decision copy(
            Decision source,
            String decisionId,
            DecisionOutcome outcome,
            Instant createdAt) {
        return new Decision(
                new DecisionId(decisionId),
                source.tenantId(),
                source.decisionPointId(),
                source.requestIdempotencyKey(),
                outcome,
                source.selectedOption(),
                source.reason(),
                source.decidedBy(),
                source.deciderAuthoritySnapshotRef(),
                source.subjectHash(),
                createdAt);
    }

    private static BuildSession releaseReviewSession(BuildSession source, String suffix) {
        return new BuildSession(
                source.buildSessionId(),
                source.tenantId(),
                source.projectId(),
                source.productLineId(),
                source.requestIdempotencyKey(),
                source.createdBy(),
                BuildSessionStatus.WAITING_RELEASE_REVIEW,
                BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                source.requirementsArtifactRef(),
                source.requirementsHash(),
                source.selectedManagerWorkerBinding(),
                source.selectedCodingWorkerBinding(),
                source.currentBlueprintRef(),
                java.util.Optional.of(new CandidateId("candidate-" + suffix)),
                java.util.Optional.of(PersistenceFixtures.HASH_A),
                source.currentCertificationId(),
                source.repairRound(),
                source.maxRepairRounds(),
                source.startedAt(),
                source.deadlineAt(),
                source.cancellationRequestedAt(),
                source.terminalCode(),
                source.terminalMessage(),
                source.version(),
                source.createdAt(),
                source.updatedAt());
    }

    private static BuildSession changedCandidate(BuildSession source) {
        return new BuildSession(
                source.buildSessionId(),
                source.tenantId(),
                source.projectId(),
                source.productLineId(),
                source.requestIdempotencyKey(),
                source.createdBy(),
                source.status(),
                source.currentPhase(),
                source.requirementsArtifactRef(),
                source.requirementsHash(),
                source.selectedManagerWorkerBinding(),
                source.selectedCodingWorkerBinding(),
                source.currentBlueprintRef(),
                java.util.Optional.of(new CandidateId("candidate-changed")),
                java.util.Optional.of(PersistenceFixtures.HASH_B),
                source.currentCertificationId(),
                source.repairRound(),
                source.maxRepairRounds(),
                source.startedAt(),
                source.deadlineAt(),
                source.cancellationRequestedAt(),
                source.terminalCode(),
                source.terminalMessage(),
                source.version() + 1,
                source.createdAt(),
                source.updatedAt().plusMillis(1));
    }

    private static BuildSession candidateReady(BuildSession source) {
        return new BuildSession(
                source.buildSessionId(),
                source.tenantId(),
                source.projectId(),
                source.productLineId(),
                source.requestIdempotencyKey(),
                source.createdBy(),
                BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE,
                source.currentPhase(),
                source.requirementsArtifactRef(),
                source.requirementsHash(),
                source.selectedManagerWorkerBinding(),
                source.selectedCodingWorkerBinding(),
                source.currentBlueprintRef(),
                source.currentCandidateId(),
                source.currentCandidateHash(),
                source.currentCertificationId(),
                source.repairRound(),
                source.maxRepairRounds(),
                source.startedAt(),
                source.deadlineAt(),
                source.cancellationRequestedAt(),
                source.terminalCode(),
                source.terminalMessage(),
                source.version() + 1,
                source.createdAt(),
                source.updatedAt().plusSeconds(1));
    }

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private record Fixture(
            JdbcBuildSessionRepository sessions,
            JdbcDecisionPointRepository points,
            JdbcDecisionRepository decisions,
            DecisionRecordingService service,
            BuildSession session,
            io.github.flowerjvm.factory.application.decision.DecisionPoint point,
            Decision decision) {}
}
