package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.PersistenceFixtures.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.application.decision.DecisionPointStatus;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcLedgerRepositoriesTest {
    @Test
    void decisionSubjectLookupRequiresTheExactDecisionType() {
        JdbcDataSource dataSource = FactoryDatabaseMigrationsTest.h2("decision_subject_type");
        FactoryDatabaseMigrations.migrate(dataSource);
        var sessions = new JdbcBuildSessionRepository(dataSource);
        var points = new JdbcDecisionPointRepository(dataSource);
        var session = PersistenceFixtures.buildSession("decision-subject-type");
        DecisionPoint release = PersistenceFixtures.decisionPoint(session, "decision-subject-type");
        DecisionPoint unrelated = new DecisionPoint(
                new DecisionPointId("point-unrelated-type"),
                release.tenantId(), release.buildSessionId(), "DESIGN_REVIEW", release.status(),
                release.subjectType(), release.subjectId(), release.subjectVersion(), release.subjectHash(),
                release.questionArtifactRef(), release.optionsSchemaId(), release.requiredPermissions(),
                release.minimumApprovers(), release.policySnapshotRef(), release.openedAt().plusSeconds(1),
                release.dueAt(), release.decidedAt(), release.terminalDecisionId(), release.version());
        sessions.create(session);
        points.create(release);
        points.create(unrelated);

        assertEquals(release, points.findLatestByBuildSessionAndSubject(
                release.tenantId(), release.buildSessionId(), DecisionPoint.RELEASE_REVIEW_TYPE,
                release.subjectType(), release.subjectId(), release.subjectHash()).orElseThrow());
        assertEquals(unrelated, points.findLatestByBuildSessionAndSubject(
                release.tenantId(), release.buildSessionId(), "DESIGN_REVIEW",
                release.subjectType(), release.subjectId(), release.subjectHash()).orElseThrow());
        assertTrue(points.findLatestByBuildSessionAndSubject(
                release.tenantId(), release.buildSessionId(), "OTHER_REVIEW",
                release.subjectType(), release.subjectId(), release.subjectHash()).isEmpty());
    }

    @Test
    void roundTripsEveryPr2LedgerAndPreservesTenantScope() {
        JdbcDataSource dataSource = FactoryDatabaseMigrationsTest.h2("roundtrip");
        FactoryDatabaseMigrations.migrate(dataSource);

        var buildSessions = new JdbcBuildSessionRepository(dataSource);
        var workOrders = new JdbcWorkOrderRepository(dataSource);
        var workerRuns = new JdbcWorkerRunRepository(dataSource);
        var decisionPoints = new JdbcDecisionPointRepository(dataSource);
        var decisions = new JdbcDecisionRepository(dataSource);
        var outbox = new JdbcDispatchOutboxRepository(dataSource);

        var buildSession = PersistenceFixtures.buildSession("roundtrip");
        var workOrder = PersistenceFixtures.workOrder(buildSession, "roundtrip");
        var workerRun = PersistenceFixtures.workerRun(buildSession, workOrder, "roundtrip");
        var dispatchOutbox = PersistenceFixtures.outbox(workerRun, "roundtrip");
        var decisionPoint = PersistenceFixtures.decisionPoint(buildSession, "roundtrip");
        var decision = PersistenceFixtures.decision(decisionPoint, "roundtrip");

        buildSessions.create(buildSession);
        workOrders.create(workOrder);
        workerRuns.create(workerRun);
        outbox.create(dispatchOutbox);
        decisionPoints.create(decisionPoint);
        decisions.create(decision);

        assertEquals(buildSession, buildSessions.find(TENANT, buildSession.buildSessionId()).orElseThrow());
        assertEquals(workOrder, workOrders.find(TENANT, workOrder.workOrderId()).orElseThrow());
        assertEquals(workerRun, workerRuns.find(TENANT, workerRun.workerRunId()).orElseThrow());
        assertEquals(dispatchOutbox, outbox.find(TENANT, dispatchOutbox.outboxId()).orElseThrow());
        assertEquals(decisionPoint, decisionPoints.find(TENANT, decisionPoint.decisionPointId()).orElseThrow());
        assertEquals(decision, decisions.find(TENANT, decision.decisionId()).orElseThrow());

        TenantId otherTenant = new TenantId("tenant-b");
        assertTrue(buildSessions.find(otherTenant, buildSession.buildSessionId()).isEmpty());
        assertTrue(workerRuns.find(otherTenant, workerRun.workerRunId()).isEmpty());
        assertThrows(DuplicateLedgerRecordException.class, () -> buildSessions.create(buildSession));
    }

    @Test
    void buildSessionCasRejectsProductLineMutation() {
        JdbcDataSource dataSource = FactoryDatabaseMigrationsTest.h2("build_session_product_line_cas");
        FactoryDatabaseMigrations.migrate(dataSource);
        var repository = new JdbcBuildSessionRepository(dataSource);
        BuildSession expected = PersistenceFixtures.buildSession("product-line-cas");
        repository.create(expected);
        BuildSession cancelling = expected.requestCancellation(PersistenceFixtures.NOW.plusSeconds(1));
        BuildSession rebound = withProductLine(cancelling, new ProductLineId("different-line"));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> repository.compareAndSet(expected, rebound));

        assertTrue(failure.getMessage().contains("productLineId"));
        assertEquals(expected, repository.find(TENANT, expected.buildSessionId()).orElseThrow());
    }

    @Test
    void preparesWorkerTransitionAndOutboxInOneTransaction() {
        JdbcDataSource dataSource = FactoryDatabaseMigrationsTest.h2("dispatch_transaction");
        FactoryDatabaseMigrations.migrate(dataSource);
        var buildSessions = new JdbcBuildSessionRepository(dataSource);
        var workOrders = new JdbcWorkOrderRepository(dataSource);
        var workerRuns = new JdbcWorkerRunRepository(dataSource);
        var outbox = new JdbcDispatchOutboxRepository(dataSource);
        var transaction = new JdbcWorkerDispatchTransaction(
                dataSource,
                new ObjectMapper(),
                Clock.fixed(PersistenceFixtures.NOW, ZoneOffset.UTC));

        var buildSession = PersistenceFixtures.buildSession(
                "dispatch", "request-dispatch", BuildSessionPhase.GENERATE_CANDIDATE);
        var workOrder = PersistenceFixtures.workOrder(buildSession, "dispatch");
        var workerRun = PersistenceFixtures.workerRun(buildSession, workOrder, "dispatch");
        var fixtureOutbox = PersistenceFixtures.outbox(workerRun, "dispatch");
        var dispatchOutbox = new DispatchOutbox(
                fixtureOutbox.outboxId(),
                fixtureOutbox.tenantId(),
                fixtureOutbox.operationType(),
                fixtureOutbox.aggregateType(),
                fixtureOutbox.aggregateId(),
                fixtureOutbox.operationId(),
                workOrder.inputArtifactManifestRef(),
                fixtureOutbox.status(),
                fixtureOutbox.availableAt(),
                fixtureOutbox.attemptCount(),
                fixtureOutbox.lastCode(),
                fixtureOutbox.version(),
                fixtureOutbox.createdAt(),
                fixtureOutbox.updatedAt());
        var dispatching = workerRun.startDispatch(
                dispatchOutbox.outboxId(),
                "action-run-winner",
                io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds
                        .hashAttemptToken("attempt-token-winner"),
                PersistenceFixtures.NOW);
        buildSessions.create(buildSession);
        workOrders.create(workOrder);
        workerRuns.create(workerRun);
        ActionRunFixtures.createWorkerDispatchOwner(dataSource, workerRun, "action-run-winner");

        assertTrue(transaction.prepare(workOrder, workerRun, dispatching, dispatchOutbox));
        assertEquals(dispatching, workerRuns.find(TENANT, workerRun.workerRunId()).orElseThrow());
        assertEquals(dispatchOutbox, outbox.find(TENANT, dispatchOutbox.outboxId()).orElseThrow());

        DispatchOutboxId losingOutboxId = new DispatchOutboxId("outbox-dispatch-loser");
        var losingTransition = workerRun.startDispatch(
                losingOutboxId,
                "action-run-loser",
                io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds
                        .hashAttemptToken("attempt-token-loser"),
                PersistenceFixtures.NOW.plusMillis(1));
        var losingOutbox = new io.github.flowerjvm.factory.application.outbox.DispatchOutbox(
                losingOutboxId,
                workerRun.tenantId(),
                dispatchOutbox.operationType(),
                dispatchOutbox.aggregateType(),
                dispatchOutbox.aggregateId(),
                dispatchOutbox.operationId(),
                dispatchOutbox.payloadArtifactRef(),
                dispatchOutbox.status(),
                dispatchOutbox.availableAt(),
                dispatchOutbox.attemptCount(),
                dispatchOutbox.lastCode(),
                dispatchOutbox.version(),
                dispatchOutbox.createdAt(),
                dispatchOutbox.updatedAt());

        assertFalse(transaction.prepare(workOrder, workerRun, losingTransition, losingOutbox));
        assertTrue(outbox.find(TENANT, losingOutboxId).isEmpty());
    }

    @Test
    void decisionPointAndOutboxMutationsRequireExactVersionCas() {
        JdbcDataSource dataSource = FactoryDatabaseMigrationsTest.h2("other_cas_ledgers");
        FactoryDatabaseMigrations.migrate(dataSource);
        var buildSessions = new JdbcBuildSessionRepository(dataSource);
        var decisionPoints = new JdbcDecisionPointRepository(dataSource);
        var decisions = new JdbcDecisionRepository(dataSource);
        var outboxRepository = new JdbcDispatchOutboxRepository(dataSource);
        var buildSession = PersistenceFixtures.buildSession("other-cas");
        var point = PersistenceFixtures.decisionPoint(buildSession, "other-cas");
        var decision = PersistenceFixtures.decision(point, "other-cas");
        DecisionId decisionId = new DecisionId("decision-other-cas");
        var decided = new DecisionPoint(
                point.decisionPointId(),
                point.tenantId(),
                point.buildSessionId(),
                point.type(),
                DecisionPointStatus.APPROVED,
                point.subjectType(),
                point.subjectId(),
                point.subjectVersion(),
                point.subjectHash(),
                point.questionArtifactRef(),
                point.optionsSchemaId(),
                point.requiredPermissions(),
                point.minimumApprovers(),
                point.policySnapshotRef(),
                point.openedAt(),
                point.dueAt(),
                java.util.Optional.of(PersistenceFixtures.NOW.plusSeconds(1)),
                java.util.Optional.of(decisionId),
                1);
        var workOrder = PersistenceFixtures.workOrder(buildSession, "other-cas");
        var workerRun = PersistenceFixtures.workerRun(buildSession, workOrder, "other-cas");
        var outbox = PersistenceFixtures.outbox(workerRun, "other-cas");
        var dispatching = outbox.claimForSubmission(
                "claim-other-cas",
                outbox.availableAt().plusMillis(1),
                Duration.ofMinutes(1));

        buildSessions.create(buildSession);
        decisionPoints.create(point);
        decisions.create(decision);
        outboxRepository.create(outbox);

        assertTrue(decisionPoints.compareAndSet(point, decided));
        assertFalse(decisionPoints.compareAndSet(point, decided));
        assertEquals(decided, decisionPoints.find(TENANT, point.decisionPointId()).orElseThrow());
        assertTrue(outboxRepository.compareAndSet(outbox, dispatching));
        assertFalse(outboxRepository.compareAndSet(outbox, dispatching));
        assertEquals(dispatching, outboxRepository.find(TENANT, outbox.outboxId()).orElseThrow());
    }

    private static BuildSession withProductLine(BuildSession source, ProductLineId productLineId) {
        return new BuildSession(
                source.buildSessionId(),
                source.tenantId(),
                source.projectId(),
                productLineId,
                source.requestIdempotencyKey(),
                source.createdBy(),
                source.status(),
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
                source.version(),
                source.createdAt(),
                source.updatedAt());
    }
}
