package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

final class JdbcLedgerOwnershipConstraints {
    private JdbcLedgerOwnershipConstraints() {}

    static void assertOneActiveBuildSessionPerRequest(DataSource dataSource, String prefix) throws Exception {
        String requestKey = "shared-request-" + prefix;
        BuildSession first = PersistenceFixtures.buildSession(prefix + "-build-a", requestKey);
        BuildSession second = PersistenceFixtures.buildSession(prefix + "-build-b", requestKey);
        JdbcBuildSessionRepository repository = new JdbcBuildSessionRepository(dataSource);

        List<Boolean> outcomes = raceCreates(
                () -> new JdbcBuildSessionRepository(dataSource).create(first),
                () -> new JdbcBuildSessionRepository(dataSource).create(second));
        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());

        BuildSession winner = outcomes.get(0) ? first : second;
        BuildSession terminal = PersistenceFixtures.terminal(
                winner,
                BuildSessionStatus.SUCCEEDED,
                BuildSessionPhase.COMPLETE,
                "BUILD_SUCCEEDED");
        assertTrue(repository.compareAndSet(winner, terminal));

        BuildSession replacement = PersistenceFixtures.buildSession(prefix + "-build-c", requestKey);
        repository.create(replacement);
        assertEquals(
                replacement,
                repository.find(replacement.tenantId(), replacement.buildSessionId()).orElseThrow());
    }

    static void assertOneActiveWorkerAttemptPerWorkOrder(DataSource dataSource, String prefix) throws Exception {
        BuildSession buildSession = PersistenceFixtures.buildSession(
                prefix + "-worker-session",
                "request-" + prefix + "-worker-session",
                BuildSessionPhase.GENERATE_CANDIDATE);
        var workOrder = PersistenceFixtures.workOrder(buildSession, prefix + "-worker-order");
        var buildSessions = new JdbcBuildSessionRepository(dataSource);
        var workOrders = new JdbcWorkOrderRepository(dataSource);
        var workerRuns = new JdbcWorkerRunRepository(dataSource);
        buildSessions.create(buildSession);
        workOrders.create(workOrder);

        WorkerRunRecord first = PersistenceFixtures.workerRun(
                buildSession, workOrder, prefix + "-worker-a", 1);
        WorkerRunRecord second = PersistenceFixtures.workerRun(
                buildSession, workOrder, prefix + "-worker-b", 2);
        List<Boolean> outcomes = raceCreates(
                () -> new JdbcWorkerRunRepository(dataSource).create(first),
                () -> new JdbcWorkerRunRepository(dataSource).create(second));
        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());

        WorkerRunRecord winner = outcomes.get(0) ? first : second;
        var fixtureOutbox = PersistenceFixtures.outbox(winner, prefix + "-worker-terminal");
        DispatchOutbox dispatchOutbox = new DispatchOutbox(
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
        String actionRunId = "action-" + prefix + "-worker-terminal";
        ActionRunFixtures.createWorkerDispatchOwner(dataSource, winner, actionRunId);
        var dispatching = winner.startDispatch(
                dispatchOutbox.outboxId(),
                actionRunId,
                WorkerDispatchOperationIds.hashAttemptToken("token-" + prefix),
                PersistenceFixtures.NOW.plusSeconds(1));
        assertTrue(new JdbcWorkerDispatchTransaction(
                        dataSource,
                        new ObjectMapper(),
                        Clock.fixed(PersistenceFixtures.NOW.plusSeconds(1), ZoneOffset.UTC))
                .prepare(workOrder, winner, dispatching, dispatchOutbox));
        var waiting = dispatching.awaitExternal(
                "external-" + prefix,
                PersistenceFixtures.NOW.plusSeconds(2));
        assertTrue(workerRuns.compareAndSet(dispatching, waiting));
        WorkerRunRecord terminal = waiting.complete(
                io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                "WORKER_FAILED",
                "Worker failed",
                io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition.NEVER,
                PersistenceFixtures.NOW.plusSeconds(3));
        assertTrue(workerRuns.compareAndSet(waiting, terminal));

        WorkerRunRecord replacement = PersistenceFixtures.workerRun(
                buildSession, workOrder, prefix + "-worker-c", 3);
        workerRuns.create(replacement);
        assertEquals(
                replacement,
                workerRuns.find(replacement.tenantId(), replacement.workerRunId()).orElseThrow());
    }

    static void assertWorkerRunCannotCrossSessionAndWorkOrder(DataSource dataSource, String prefix) {
        BuildSession owningSession = PersistenceFixtures.buildSession(prefix + "-owner-session");
        BuildSession otherSession = PersistenceFixtures.buildSession(prefix + "-other-session");
        var workOrder = PersistenceFixtures.workOrder(owningSession, prefix + "-owned-order");
        var buildSessions = new JdbcBuildSessionRepository(dataSource);
        var workOrders = new JdbcWorkOrderRepository(dataSource);
        var workerRuns = new JdbcWorkerRunRepository(dataSource);
        buildSessions.create(owningSession);
        buildSessions.create(otherSession);
        workOrders.create(workOrder);

        WorkerRunRecord mismatched = PersistenceFixtures.workerRun(
                otherSession, workOrder, prefix + "-mismatched-worker", 1);
        assertThrows(FactoryPersistenceException.class, () -> workerRuns.create(mismatched));
        assertTrue(workerRuns.find(mismatched.tenantId(), mismatched.workerRunId()).isEmpty());
    }

    static void assertSupersededWorkOrderMustExistInTenant(DataSource dataSource, String prefix) {
        BuildSession buildSession = PersistenceFixtures.buildSession(prefix + "-lineage-session");
        var buildSessions = new JdbcBuildSessionRepository(dataSource);
        var workOrders = new JdbcWorkOrderRepository(dataSource);
        buildSessions.create(buildSession);

        var missingPredecessor = PersistenceFixtures.workOrder(
                buildSession,
                prefix + "-missing-lineage",
                Optional.of(new io.github.flowerjvm.factory.contracts.ids.WorkOrderId(
                        "work-" + prefix + "-does-not-exist")));
        assertThrows(FactoryPersistenceException.class, () -> workOrders.create(missingPredecessor));

        var predecessor = PersistenceFixtures.workOrder(buildSession, prefix + "-predecessor");
        workOrders.create(predecessor);
        var successor = PersistenceFixtures.workOrder(
                buildSession,
                prefix + "-successor",
                Optional.of(predecessor.workOrderId()));
        workOrders.create(successor);
        assertEquals(
                successor,
                workOrders.find(successor.tenantId(), successor.workOrderId()).orElseThrow());
    }

    static void assertDecisionReferencesArePointAndHashBound(DataSource dataSource, String prefix) {
        BuildSession buildSession = PersistenceFixtures.buildSession(prefix + "-decision-session");
        var firstPoint = PersistenceFixtures.decisionPoint(buildSession, prefix + "-point-a");
        var secondPoint = PersistenceFixtures.decisionPoint(buildSession, prefix + "-point-b");
        var buildSessions = new JdbcBuildSessionRepository(dataSource);
        var decisionPoints = new JdbcDecisionPointRepository(dataSource);
        var decisions = new JdbcDecisionRepository(dataSource);
        buildSessions.create(buildSession);
        decisionPoints.create(firstPoint);
        decisionPoints.create(secondPoint);

        var wrongHash = PersistenceFixtures.decision(
                firstPoint, prefix + "-wrong-hash", PersistenceFixtures.HASH_B);
        assertThrows(FactoryPersistenceException.class, () -> decisions.create(wrongHash));

        var otherPointDecision = PersistenceFixtures.decision(secondPoint, prefix + "-other-point");
        decisions.create(otherPointDecision);
        var wrongTerminal = PersistenceFixtures.terminalDecisionPoint(
                firstPoint, otherPointDecision.decisionId());
        assertThrows(
                FactoryPersistenceException.class,
                () -> decisionPoints.compareAndSet(firstPoint, wrongTerminal));

        var matchingDecision = PersistenceFixtures.decision(firstPoint, prefix + "-matching");
        decisions.create(matchingDecision);
        var terminal = PersistenceFixtures.terminalDecisionPoint(firstPoint, matchingDecision.decisionId());
        assertTrue(decisionPoints.compareAndSet(firstPoint, terminal));
        assertEquals(
                terminal,
                decisionPoints.find(terminal.tenantId(), terminal.decisionPointId()).orElseThrow());
    }

    private static List<Boolean> raceCreates(CheckedCreate first, CheckedCreate second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(3);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> firstResult = executor.submit(createTask(barrier, first));
            Future<Boolean> secondResult = executor.submit(createTask(barrier, second));
            barrier.await(5, TimeUnit.SECONDS);
            return List.of(
                    firstResult.get(10, TimeUnit.SECONDS),
                    secondResult.get(10, TimeUnit.SECONDS));
        }
    }

    private static Callable<Boolean> createTask(CyclicBarrier barrier, CheckedCreate create) {
        return () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                create.run();
                return true;
            } catch (DuplicateLedgerRecordException expected) {
                return false;
            }
        };
    }

    @FunctionalInterface
    private interface CheckedCreate {
        void run();
    }
}
