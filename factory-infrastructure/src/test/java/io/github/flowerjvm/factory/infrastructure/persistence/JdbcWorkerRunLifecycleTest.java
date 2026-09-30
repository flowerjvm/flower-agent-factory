package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcWorkerRunLifecycleTest {
    private static final Instant DISPATCHED_AT = PersistenceFixtures.NOW.plusSeconds(1);

    @Test
    void createRejectsTerminalWorkerRunEvenWhenItsShapeIsOtherwiseValid() {
        Fixture fixture = fixture("terminal-create");
        WorkerRunRecord terminal = fixture.workerRun()
                .startDispatch(
                        new DispatchOutboxId("outbox-terminal-create"),
                        "action-terminal-create",
                        WorkerDispatchOperationIds.hashAttemptToken("token-terminal-create"),
                        DISPATCHED_AT)
                .awaitExternal("external-terminal-create", DISPATCHED_AT.plusSeconds(1))
                .complete(
                        WorkerRunStatus.FAILED,
                        Optional.empty(),
                        Optional.empty(),
                        "WORKER_FAILED",
                        "Worker failed",
                        WorkerRetryDisposition.NEVER,
                        DISPATCHED_AT.plusSeconds(2));

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.repository().create(terminal));
        assertTrue(fixture.repository()
                .find(terminal.tenantId(), terminal.workerRunId())
                .isEmpty());
    }

    @Test
    void requestedCannotCasDirectlyToPhantomTerminal() {
        Fixture fixture = persistedFixture("phantom-terminal");
        WorkerRunRecord terminal = terminalAtNextVersion(fixture.workerRun(), "phantom-terminal");

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.repository().compareAndSet(fixture.workerRun(), terminal));
        assertEquals(
                fixture.workerRun(),
                fixture.repository()
                        .find(fixture.workerRun().tenantId(), fixture.workerRun().workerRunId())
                        .orElseThrow());
    }

    @Test
    void postDispatchOwnershipCannotBeRewritten() {
        Fixture fixture = persistedFixture("ownership-rewrite");
        PreparedDispatch prepared = prepare(fixture, "ownership-rewrite");
        assertTrue(prepared.prepared());
        WorkerRunRecord rewritten = waitingWithActionRunId(
                prepared.dispatching(),
                "different-action-owner",
                DISPATCHED_AT.plusSeconds(1));

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.repository().compareAndSet(prepared.dispatching(), rewritten));
        assertEquals(
                prepared.dispatching(),
                fixture.repository()
                        .find(prepared.dispatching().tenantId(), prepared.dispatching().workerRunId())
                        .orElseThrow());
    }

    @Test
    void legalRequestedDispatchingWaitingExternalTerminalPathPersists() {
        Fixture fixture = persistedFixture("legal-path");
        PreparedDispatch prepared = dispatch(fixture, "legal-path");

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.repository().compareAndSet(fixture.workerRun(), prepared.dispatching()));
        assertTrue(prepared.prepared());
        WorkerRunRecord waiting = prepared.dispatching()
                .awaitExternal("external-legal-path", DISPATCHED_AT.plusSeconds(1));
        assertTrue(fixture.repository().compareAndSet(prepared.dispatching(), waiting));
        WorkerRunRecord succeeded = waiting.complete(
                WorkerRunStatus.SUCCEEDED,
                Optional.of(new ArtifactReference("artifact:result:legal-path")),
                Optional.of(PersistenceFixtures.HASH_A),
                "WORKER_SUCCEEDED",
                "Worker succeeded",
                WorkerRetryDisposition.NEVER,
                DISPATCHED_AT.plusSeconds(2));
        assertTrue(fixture.repository().compareAndSet(waiting, succeeded));
        assertEquals(
                succeeded,
                fixture.repository()
                        .find(succeeded.tenantId(), succeeded.workerRunId())
                        .orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.repository().compareAndSet(succeeded, succeeded));
    }

    @Test
    void canonicalCompletionMayWinAfterCancellationIntentWasPersisted() {
        Fixture fixture = persistedFixture("completion-wins-cancel-race");
        PreparedDispatch prepared = dispatch(fixture, "completion-wins-cancel-race");
        assertTrue(prepared.prepared());
        WorkerRunRecord waiting = prepared.dispatching()
                .awaitExternal("external-completion-wins", DISPATCHED_AT.plusSeconds(1));
        assertTrue(fixture.repository().compareAndSet(prepared.dispatching(), waiting));
        WorkerRunRecord cancelRequested = waiting.requestCancellation(DISPATCHED_AT.plusSeconds(2));
        assertTrue(fixture.repository().compareAndSet(waiting, cancelRequested));

        WorkerRunRecord completionWinner = cancelRequested.complete(
                WorkerRunStatus.SUCCEEDED,
                Optional.of(new ArtifactReference("artifact:result:completion-wins-cancel-race")),
                Optional.of(PersistenceFixtures.HASH_A),
                "WORKER_SUCCEEDED",
                "Worker completion won the Action first-terminal race",
                WorkerRetryDisposition.NEVER,
                DISPATCHED_AT.plusSeconds(3));

        assertTrue(fixture.repository().compareAndSet(cancelRequested, completionWinner));
        assertEquals(
                completionWinner,
                fixture.repository().find(
                                completionWinner.tenantId(), completionWinner.workerRunId())
                        .orElseThrow());
    }

    @Test
    void dispatchClaimRequiresActionRunBoundToSameWorkerAndWorkOrder() {
        Fixture fixture = persistedFixture("wrong-action-owner");
        String actionRunId = "action-wrong-action-owner";
        ActionRunFixtures.createWorkerDispatchOwner(
                fixture.dataSource(),
                fixture.workerRun(),
                actionRunId,
                fixture.workOrder().workOrderId().value(),
                "another-worker-run");
        PreparedDispatch prepared = prepareWithoutActionSeed(fixture, "wrong-action-owner", actionRunId);

        assertFalse(prepared.prepared());
        assertEquals(
                fixture.workerRun(),
                fixture.repository()
                        .find(fixture.workerRun().tenantId(), fixture.workerRun().workerRunId())
                        .orElseThrow());
        assertTrue(new JdbcDispatchOutboxRepository(fixture.dataSource())
                .find(fixture.workerRun().tenantId(), prepared.outbox().outboxId())
                .isEmpty());
    }

    private static PreparedDispatch dispatch(Fixture fixture, String suffix) {
        String actionRunId = "action-" + suffix;
        ActionRunFixtures.createWorkerDispatchOwner(
                fixture.dataSource(), fixture.workerRun(), actionRunId);
        return prepareWithoutActionSeed(fixture, suffix, actionRunId);
    }

    private static PreparedDispatch prepare(Fixture fixture, String suffix) {
        return dispatch(fixture, suffix);
    }

    private static PreparedDispatch prepareWithoutActionSeed(
            Fixture fixture,
            String suffix,
            String actionRunId) {
        DispatchOutbox outbox = outbox(fixture, suffix);
        WorkerRunRecord dispatching = fixture.workerRun().startDispatch(
                outbox.outboxId(),
                actionRunId,
                WorkerDispatchOperationIds.hashAttemptToken("token-" + suffix),
                DISPATCHED_AT);
        boolean prepared = new JdbcWorkerDispatchTransaction(
                        fixture.dataSource(),
                        new ObjectMapper(),
                        Clock.fixed(DISPATCHED_AT, ZoneOffset.UTC))
                .prepare(fixture.workOrder(), fixture.workerRun(), dispatching, outbox);
        return new PreparedDispatch(dispatching, outbox, prepared);
    }

    private static DispatchOutbox outbox(Fixture fixture, String suffix) {
        return new DispatchOutbox(
                new DispatchOutboxId("outbox-" + suffix),
                fixture.workerRun().tenantId(),
                "WORKER_DISPATCH",
                "WORKER_RUN",
                fixture.workerRun().workerRunId().value(),
                fixture.workerRun().operationId(),
                fixture.workOrder().inputArtifactManifestRef(),
                DispatchOutboxStatus.PENDING,
                DISPATCHED_AT,
                0,
                Optional.empty(),
                0,
                DISPATCHED_AT,
                DISPATCHED_AT);
    }

    private static Fixture persistedFixture(String suffix) {
        Fixture fixture = fixture(suffix);
        new JdbcBuildSessionRepository(fixture.dataSource()).create(fixture.buildSession());
        new JdbcWorkOrderRepository(fixture.dataSource()).create(fixture.workOrder());
        fixture.repository().create(fixture.workerRun());
        return fixture;
    }

    private static Fixture fixture(String suffix) {
        JdbcDataSource dataSource = FactoryDatabaseMigrationsTest.h2("worker_lifecycle_" + suffix);
        FactoryDatabaseMigrations.migrate(dataSource);
        BuildSession buildSession = PersistenceFixtures.buildSession(
                suffix,
                "request-" + suffix,
                BuildSessionPhase.GENERATE_CANDIDATE);
        WorkOrder workOrder = PersistenceFixtures.workOrder(buildSession, suffix);
        WorkerRunRecord workerRun = PersistenceFixtures.workerRun(buildSession, workOrder, suffix);
        return new Fixture(
                dataSource,
                buildSession,
                workOrder,
                workerRun,
                new JdbcWorkerRunRepository(dataSource));
    }

    private static WorkerRunRecord terminalAtNextVersion(WorkerRunRecord requested, String suffix) {
        Instant completedAt = requested.updatedAt().plusSeconds(1);
        return new WorkerRunRecord(
                requested.workerRunId(),
                requested.tenantId(),
                requested.buildSessionId(),
                requested.workOrderId(),
                requested.attemptNo(),
                requested.workerBindingId(),
                requested.workerAdapterVersion(),
                requested.workerCapabilitySnapshot(),
                WorkerRunStatus.SUCCEEDED,
                Optional.of("action-" + suffix),
                requested.operationId(),
                Optional.of(WorkerDispatchOperationIds.hashAttemptToken("token-" + suffix)),
                Optional.of("external-" + suffix),
                Optional.of(new DispatchOutboxId("outbox-" + suffix)),
                Optional.of(requested.updatedAt()),
                requested.deadlineAt(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(completedAt),
                Optional.of(new ArtifactReference("artifact:result:" + suffix)),
                Optional.of(PersistenceFixtures.HASH_A),
                Optional.of("WORKER_SUCCEEDED"),
                Optional.of("Worker succeeded"),
                Optional.of(WorkerRetryDisposition.NEVER),
                requested.version() + 1,
                requested.createdAt(),
                completedAt);
    }

    private static WorkerRunRecord waitingWithActionRunId(
            WorkerRunRecord dispatching,
            String actionRunId,
            Instant observedAt) {
        return new WorkerRunRecord(
                dispatching.workerRunId(),
                dispatching.tenantId(),
                dispatching.buildSessionId(),
                dispatching.workOrderId(),
                dispatching.attemptNo(),
                dispatching.workerBindingId(),
                dispatching.workerAdapterVersion(),
                dispatching.workerCapabilitySnapshot(),
                WorkerRunStatus.WAITING_EXTERNAL,
                Optional.of(actionRunId),
                dispatching.operationId(),
                dispatching.attemptTokenHash(),
                Optional.of("external-ownership-rewrite"),
                dispatching.dispatchOutboxId(),
                dispatching.startedAt(),
                dispatching.deadlineAt(),
                dispatching.heartbeatAt(),
                dispatching.cancelRequestedAt(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dispatching.version() + 1,
                dispatching.createdAt(),
                observedAt);
    }

    private record Fixture(
            JdbcDataSource dataSource,
            BuildSession buildSession,
            WorkOrder workOrder,
            WorkerRunRecord workerRun,
            JdbcWorkerRunRepository repository) {}

    private record PreparedDispatch(
            WorkerRunRecord dispatching,
            DispatchOutbox outbox,
            boolean prepared) {}
}
