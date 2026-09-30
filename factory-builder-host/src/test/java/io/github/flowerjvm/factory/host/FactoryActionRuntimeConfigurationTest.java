package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.action.WorkerDispatchIdempotencyKeys;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxRepository;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.application.work.WorkerRunRepository;
import io.github.flowerjvm.factory.application.work.WorkerActionRunRecovery;
import io.github.flowerjvm.factory.application.work.WorkerCompletion;
import io.github.flowerjvm.factory.application.work.WorkerCompletionActionProjection;
import io.github.flowerjvm.factory.application.work.WorkerCompletionService;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapability;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class FactoryActionRuntimeConfigurationTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final BuildSessionId SESSION = new BuildSessionId("session-1");
    private static final WorkOrderId WORK_ORDER = new WorkOrderId("work-order-1");
    private static final WorkerRunId WORKER_RUN = new WorkerRunId("worker-run-1");
    private static final String LOGICAL_WORK_ORDER_KEY = "work-order:session-1:generate:1";

    @Test
    void terminalActionResultRepairsWorkerRunAfterCrashBeforeDomainCas() {
        DataSource dataSource = dataSource();
        WorkerRunRecord waiting;
        WorkerCompletion completion;
        ActionExecutionResult canonical;

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(FactoryActionRuntimeConfiguration.class);
            context.refresh();
            Instant now = Instant.now();
            seed(
                    context.getBean(BuildSessionRepository.class),
                    context.getBean(WorkOrderRepository.class),
                    context.getBean(WorkerRunRepository.class),
                    now,
                    now.plusSeconds(3600));
            DefaultActionRuntime runtime = context.getBean(DefaultActionRuntime.class);
            assertEquals(
                    ActionExecutionStatus.ACCEPTED,
                    runtime.handle(proposal(), executionContext("completion-crash-run", true)).status());
            WorkerRunRepository durableRuns = context.getBean(WorkerRunRepository.class);
            WorkerRunRecord dispatching = durableRuns.find(TENANT, WORKER_RUN).orElseThrow();
            Instant acceptedAt = Instant.now().isBefore(dispatching.updatedAt())
                    ? dispatching.updatedAt()
                    : Instant.now();
            waiting = dispatching.awaitExternal("fake-completion-crash", acceptedAt);
            assertTrue(durableRuns.compareAndSet(dispatching, waiting));
            var owner = context.getBean(RunStore.class).find("completion-crash-run").orElseThrow();
            Instant completedAt = Instant.now().isBefore(waiting.updatedAt())
                    ? waiting.updatedAt()
                    : Instant.now();
            completion = new WorkerCompletion(
                    TENANT,
                    WORKER_RUN,
                    waiting.operationId(),
                    owner.attemptToken(),
                    WorkerRunStatus.SUCCEEDED,
                    Optional.of(new ArtifactReference("artifact:completion-crash-result")),
                    Optional.of(new ContentHash("9".repeat(64))),
                    "WORKER_SUCCEEDED",
                    "worker finished before the simulated host crash",
                    WorkerRetryDisposition.NEVER,
                    completedAt);
            canonical = WorkerCompletionActionProjection.toActionResult(completion);
            WorkerRunRepository crashBeforeCas = new WorkerRunRepository() {
                @Override public void create(WorkerRunRecord value) { durableRuns.create(value); }
                @Override public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId) {
                    return durableRuns.find(tenantId, workerRunId);
                }
                @Override public Optional<WorkerRunRecord> findLatestByWorkOrder(
                        TenantId tenantId, WorkOrderId workOrderId) {
                    return durableRuns.findLatestByWorkOrder(tenantId, workOrderId);
                }
                @Override public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
                    return false;
                }
            };
            WorkerCompletionService service = new WorkerCompletionService(
                    crashBeforeCas, runtime, Clock.systemUTC());

            assertThrows(IllegalStateException.class, () -> service.complete(completion));
            WorkerRunRecord afterCrash = durableRuns.find(TENANT, WORKER_RUN).orElseThrow();
            assertEquals(WorkerRunStatus.WAITING_EXTERNAL, afterCrash.status());
            assertEquals(waiting.version(), afterCrash.version());
            assertEquals(waiting.actionRunId(), afterCrash.actionRunId());
            assertEquals(waiting.operationId(), afterCrash.operationId());
            assertEquals(canonical, context.getBean(RunStore.class)
                    .find("completion-crash-run").orElseThrow().result());
        }

        try (var restarted = new AnnotationConfigApplicationContext()) {
            restarted.registerBean(DataSource.class, () -> dataSource);
            restarted.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            restarted.register(FactoryActionRuntimeConfiguration.class);
            restarted.refresh();
            WorkerActionRunRecovery recovery = restarted.getBean(WorkerActionRunRecovery.class);
            WorkOrder workOrder = restarted.getBean(WorkOrderRepository.class)
                    .find(TENANT, WORK_ORDER).orElseThrow();
            WorkerRunRecord current = restarted.getBean(WorkerRunRepository.class)
                    .find(TENANT, WORKER_RUN).orElseThrow();

            WorkerRunRecord repaired = recovery.reconcileTerminalCompletion(workOrder, current);
            WorkerRunRecord reloaded = restarted.getBean(WorkerRunRepository.class)
                    .find(TENANT, WORKER_RUN)
                    .orElseThrow();

            assertEquals(WorkerRunStatus.SUCCEEDED, repaired.status());
            assertEquals(completion.resultHash(), repaired.resultHash());
            assertEquals(WorkerRunStatus.SUCCEEDED, reloaded.status());
            assertEquals(completion.resultHash(), reloaded.resultHash());
            assertTrue(recovery.terminalOwnerMatches(workOrder, reloaded));
            assertEquals(canonical, restarted.getBean(DefaultActionRuntime.class).handle(
                    proposal().toBuilder().proposalId("completion-crash-replay").build(),
                    executionContext("completion-crash-replay-run", true)));
        }
    }

    @Test
    void actionAuditPersistsRequiredScopedEventsWithoutDeniedResultDisclosure() throws Exception {
        DataSource dataSource = dataSource();
        String ownerRunId = "audit-owner-run";
        String deniedRunId = "audit-denied-run";
        String privateResult = "artifact:private-audit-result";
        List<AuditRow> ownerRowsBeforeRestart;
        List<AuditRow> deniedRowsBeforeRestart;
        long auditCountBeforeRestart;

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(FactoryActionRuntimeConfiguration.class);
            context.refresh();

            Instant now = Instant.now();
            seed(
                    context.getBean(BuildSessionRepository.class),
                    context.getBean(WorkOrderRepository.class),
                    context.getBean(WorkerRunRepository.class),
                    now,
                    now.plusSeconds(3600));
            DefaultActionRuntime runtime = context.getBean(DefaultActionRuntime.class);
            assertEquals(
                    ActionExecutionStatus.ACCEPTED,
                    runtime.handle(proposal(), executionContext(ownerRunId, true)).status());

            var ownerRun = context.getBean(RunStore.class)
                    .find(ownerRunId)
                    .orElseThrow();
            ActionExecutionResult canonical = ActionExecutionResult.succeeded(Map.of(
                    "workerRunId", WORKER_RUN.value(),
                    "resultArtifactRef", privateResult));
            assertEquals(
                    canonical,
                    runtime.complete(ownerRunId, ownerRun.attemptToken(), canonical));

            ActionProposal deniedProposal = proposal().toBuilder()
                    .proposalId("audit-denied-replay-proposal")
                    .build();
            ExecutionContext deniedPrincipal = new ExecutionContext(
                    TENANT.value(),
                    "audit-denied-principal",
                    deniedRunId,
                    "trace-" + deniedRunId,
                    Map.of(
                            "actor.permissions", Set.of(),
                            "resource.type", WorkerDispatchAction.RESOURCE_TYPE,
                            "resource.id", WORK_ORDER.value()));
            ActionExecutionResult denied = runtime.handle(deniedProposal, deniedPrincipal);
            assertEquals(ActionExecutionStatus.DENIED, denied.status());
            assertEquals("POLICY_DENIED", denied.code());

            ownerRowsBeforeRestart = auditRows(dataSource, ownerRunId);
            deniedRowsBeforeRestart = auditRows(dataSource, deniedRunId);
            assertTrue(eventTypes(ownerRowsBeforeRestart).containsAll(Set.of(
                    "ACTION_PROPOSED",
                    "ACTION_EXECUTION_DEFERRED",
                    "ACTION_EXECUTION_COMPLETED")));
            assertTrue(eventTypes(deniedRowsBeforeRestart).containsAll(Set.of(
                    "POLICY_EVALUATED",
                    "ACTION_DENIED")));
            assertTrue(ownerRowsBeforeRestart.stream()
                    .allMatch(row -> row.hasScope(ownerRunId, "trace-" + ownerRunId)));
            assertTrue(deniedRowsBeforeRestart.stream()
                    .allMatch(row -> row.hasScope(deniedRunId, "trace-" + deniedRunId)));
            assertTrue(deniedRowsBeforeRestart.stream()
                    .noneMatch(row -> row.payloadJson().contains(privateResult)));
            auditCountBeforeRestart = count(dataSource, "action_audit");
        }

        try (var restartedContext = new AnnotationConfigApplicationContext()) {
            restartedContext.registerBean(DataSource.class, () -> dataSource);
            restartedContext.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            restartedContext.register(FactoryActionRuntimeConfiguration.class);
            restartedContext.refresh();

            assertTrue(restartedContext.getBean(RunStore.class).find(ownerRunId).isPresent());
            assertEquals(
                    Set.copyOf(ownerRowsBeforeRestart),
                    Set.copyOf(auditRows(dataSource, ownerRunId)));
            assertEquals(
                    Set.copyOf(deniedRowsBeforeRestart),
                    Set.copyOf(auditRows(dataSource, deniedRunId)));
            assertEquals(auditCountBeforeRestart, count(dataSource, "action_audit"));
        }
    }

    @Test
    void staleVersionCannotPoisonDuplicateReservationBeforeCorrectDispatch() throws Exception {
        DataSource dataSource = dataSource();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(FactoryActionRuntimeConfiguration.class);
            context.refresh();

            Instant now = Instant.now();
            seed(
                    context.getBean(BuildSessionRepository.class),
                    context.getBean(WorkOrderRepository.class),
                    context.getBean(WorkerRunRepository.class),
                    now,
                    now.plusSeconds(3600));
            WorkerRunRecord requested = context.getBean(WorkerRunRepository.class)
                    .find(TENANT, WORKER_RUN)
                    .orElseThrow();
            DefaultActionRuntime runtime = context.getBean(DefaultActionRuntime.class);
            ActionProposal staleProposal = proposal().toBuilder()
                    .proposalId("stale-version-proposal")
                    .input(Map.of(
                            WorkerDispatchAction.WORK_ORDER_ID, WORK_ORDER.value(),
                            WorkerDispatchAction.WORKER_RUN_ID, WORKER_RUN.value(),
                            WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION, 99L))
                    .build();

            ActionExecutionResult stale = runtime.handle(
                    staleProposal,
                    executionContext("stale-version-run", true));

            assertEquals(ActionExecutionStatus.DENIED, stale.status());
            assertEquals("POLICY_DENIED", stale.code());
            assertEquals(0L, count(dataSource, "action_duplicate"));
            assertEquals(0L, count(dataSource, "factory_dispatch_outbox"));
            assertEquals(
                    requested,
                    context.getBean(WorkerRunRepository.class)
                            .find(TENANT, WORKER_RUN)
                            .orElseThrow());

            ActionExecutionResult accepted = runtime.handle(
                    proposal(),
                    executionContext("correct-version-run", true));

            assertEquals(ActionExecutionStatus.ACCEPTED, accepted.status());
            assertEquals("ACTION_DEFERRED", accepted.code());
            assertEquals(1L, count(dataSource, "action_duplicate"));
            assertEquals(1L, count(dataSource, "factory_dispatch_outbox"));
            WorkerRunRecord dispatched = context.getBean(WorkerRunRepository.class)
                    .find(TENANT, WORKER_RUN)
                    .orElseThrow();
            assertEquals(WorkerRunStatus.DISPATCHING, dispatched.status());
            assertEquals("correct-version-run", dispatched.actionRunId().orElseThrow());
        }
    }

    @Test
    void terminalActionOwnerAfterDispatchTransactionRollbackIsFoundWithoutRedispatch() throws Exception {
        DataSource dataSource = dataSource();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(FactoryActionRuntimeConfiguration.class);
            context.refresh();

            Instant now = Instant.now();
            seed(
                    context.getBean(BuildSessionRepository.class),
                    context.getBean(WorkOrderRepository.class),
                    context.getBean(WorkerRunRepository.class),
                    now,
                    now.plusSeconds(3600));
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("""
                        ALTER TABLE factory_dispatch_outbox
                        ADD CONSTRAINT ck_test_reject_pending CHECK (status <> 'PENDING')
                        """);
            }

            ActionExecutionResult failed = context.getBean(DefaultActionRuntime.class).handle(
                    proposal(), executionContext("terminal-orphan-run", true));

            assertEquals(ActionExecutionStatus.FAILED, failed.status());
            WorkerRunRecord requested = context.getBean(WorkerRunRepository.class)
                    .find(TENANT, WORKER_RUN)
                    .orElseThrow();
            assertEquals(WorkerRunStatus.REQUESTED, requested.status());
            assertEquals(0L, count(dataSource, "factory_dispatch_outbox"));
            var orphan = context.getBean(WorkerActionRunRecovery.class)
                    .findUnclaimedOwner(
                            context.getBean(WorkOrderRepository.class)
                                    .find(TENANT, WORK_ORDER)
                                    .orElseThrow(),
                            requested)
                    .orElseThrow();
            assertEquals(
                    WorkerActionRunRecovery.UnclaimedOwnerDisposition.TERMINAL_NO_EFFECT,
                    orphan.disposition());
            assertEquals("WORKER_ACTION_ORPHAN_NO_EFFECT", orphan.code());
        }
    }

    @Test
    void deniedPrincipalCannotReadCachedResultAfterAuthorizedCompletion() throws Exception {
        DataSource dataSource = dataSource();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(FactoryActionRuntimeConfiguration.class);
            context.refresh();

            Instant now = Instant.now();
            Instant deadline = now.plusSeconds(3600);
            seed(
                    context.getBean(BuildSessionRepository.class),
                    context.getBean(WorkOrderRepository.class),
                    context.getBean(WorkerRunRepository.class),
                    now,
                    deadline);
            DefaultActionRuntime runtime = context.getBean(DefaultActionRuntime.class);
            ActionExecutionResult accepted = runtime.handle(
                    proposal(),
                    executionContext("authorized-owner-run", true));
            assertEquals(ActionExecutionStatus.ACCEPTED, accepted.status());

            WorkerRunRecord prepared = context.getBean(WorkerRunRepository.class)
                    .find(TENANT, WORKER_RUN)
                    .orElseThrow();
            var ownerRun = context.getBean(RunStore.class)
                    .find(prepared.actionRunId().orElseThrow())
                    .orElseThrow();
            String privateResult = "artifact:private-worker-result";
            ActionExecutionResult canonical = ActionExecutionResult.succeeded(Map.of(
                    "workerRunId", prepared.workerRunId().value(),
                    "resultArtifactRef", privateResult));
            assertEquals(
                    canonical,
                    runtime.complete(ownerRun.runId(), ownerRun.attemptToken(), canonical));

            ActionProposal deniedReplay = proposal().toBuilder()
                    .proposalId("denied-replay-proposal")
                    .build();
            ExecutionContext deniedPrincipal = new ExecutionContext(
                    TENANT.value(),
                    "denied-principal",
                    "denied-replay-run",
                    "trace-denied-replay-run",
                    Map.of(
                            "actor.permissions", Set.of(),
                            "resource.type", WorkerDispatchAction.RESOURCE_TYPE,
                            "resource.id", WORK_ORDER.value()));
            ActionExecutionResult denied = runtime.handle(deniedReplay, deniedPrincipal);

            assertEquals(ActionExecutionStatus.DENIED, denied.status());
            assertEquals("POLICY_DENIED", denied.code());
            assertFalse(canonical.equals(denied));
            assertFalse(denied.output().containsValue(privateResult));
            assertEquals(
                    prepared,
                    context.getBean(WorkerRunRepository.class)
                            .find(TENANT, WORKER_RUN)
                            .orElseThrow());
            assertEquals(1L, count(dataSource, "factory_dispatch_outbox"));
        }
    }

    @Test
    void concurrentFullPipelineClaimsOneActionRunAndPersistsOneDomainEffect() throws Exception {
        DataSource dataSource = dataSource();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(FactoryActionRuntimeConfiguration.class);
            context.refresh();

            Instant now = Instant.now();
            Instant deadline = now.plusSeconds(3600);
            BuildSessionRepository sessions = context.getBean(BuildSessionRepository.class);
            WorkOrderRepository orders = context.getBean(WorkOrderRepository.class);
            WorkerRunRepository runs = context.getBean(WorkerRunRepository.class);
            DispatchOutboxRepository outbox = context.getBean(DispatchOutboxRepository.class);
            seed(sessions, orders, runs, now, deadline);

            DefaultActionRuntime runtime = context.getBean(DefaultActionRuntime.class);
            ActionProposal proposal = proposal();
            List<ActionExecutionResult> competing = runConcurrently(List.of(
                    () -> runtime.handle(proposal, executionContext("action-run-1", true)),
                    () -> runtime.handle(proposal, executionContext("action-run-2", true))));
            var denied = runtime.handle(proposal, executionContext("action-run-3", false));

            assertEquals(1L, competing.stream()
                    .filter(result -> result.status() == ActionExecutionStatus.ACCEPTED)
                    .count());
            assertEquals(1L, competing.stream()
                    .filter(result -> result.status() != ActionExecutionStatus.ACCEPTED)
                    .count());
            assertTrue(competing.stream().anyMatch(result -> "ACTION_DEFERRED".equals(result.code())));
            assertEquals(ActionExecutionStatus.DENIED, denied.status());

            WorkerRunRecord prepared = runs.find(TENANT, WORKER_RUN).orElseThrow();
            assertEquals(WorkerRunStatus.DISPATCHING, prepared.status());
            assertEquals(1, prepared.version());
            assertTrue(Set.of("action-run-1", "action-run-2")
                    .contains(prepared.actionRunId().orElseThrow()));
            var durableOutbox = outbox.find(TENANT, prepared.dispatchOutboxId().orElseThrow()).orElseThrow();
            assertEquals(DispatchOutboxStatus.PENDING, durableOutbox.status());
            assertEquals(prepared.operationId(), durableOutbox.operationId());
            assertEquals(1L, count(dataSource, "factory_dispatch_outbox"));
            assertTrue(count(dataSource, "action_audit") >= 1L);
            assertEquals(3L, count(dataSource, "action_run"));

            String ownerRunId = prepared.actionRunId().orElseThrow();
            var ownerRun = context.getBean(RunStore.class).find(ownerRunId).orElseThrow();
            ActionExecutionResult canonical = ActionExecutionResult.succeeded(Map.of(
                    "workerRunId", prepared.workerRunId().value(),
                    "result", "canonical"));
            assertEquals(
                    canonical,
                    runtime.complete(ownerRunId, ownerRun.attemptToken(), canonical));

            try (var restartedContext = new AnnotationConfigApplicationContext()) {
                restartedContext.registerBean(DataSource.class, () -> dataSource);
                restartedContext.registerBean(ObjectMapper.class, () -> new ObjectMapper());
                restartedContext.register(FactoryActionRuntimeConfiguration.class);
                restartedContext.refresh();

                DefaultActionRuntime restartedRuntime = restartedContext.getBean(DefaultActionRuntime.class);
                ActionProposal replayProposal = proposal().toBuilder()
                        .proposalId("proposal-replay")
                        .build();
                ActionExecutionResult replay = restartedRuntime.handle(
                        replayProposal,
                        executionContext("action-run-replay", true));

                assertEquals(canonical, replay);
                assertEquals(
                        prepared,
                        restartedContext.getBean(WorkerRunRepository.class)
                                .find(TENANT, WORKER_RUN)
                                .orElseThrow());
                assertEquals(1L, count(dataSource, "factory_dispatch_outbox"));
                assertEquals(4L, count(dataSource, "action_run"));
            }
        }
    }

    private static void seed(
            BuildSessionRepository sessions,
            WorkOrderRepository orders,
            WorkerRunRepository runs,
            Instant now,
            Instant deadline) {
        sessions.create(new BuildSession(
                SESSION,
                TENANT,
                new ProjectId("project-1"),
                ProductLineId.AGENT_PACK,
                "request-1",
                "factory-builder",
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.GENERATE_CANDIDATE,
                new ArtifactReference("artifact:requirements"),
                hash("0"),
                Optional.empty(),
                Optional.of("coding-worker"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                3,
                now.minusSeconds(60),
                deadline,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                now.minusSeconds(60),
                now.minusSeconds(60)));
        WorkOrder workOrder = new WorkOrder(
                WORK_ORDER,
                TENANT,
                SESSION,
                "generate-candidate",
                "Generate a locked candidate",
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new ArtifactReference("artifact:instruction"),
                hash("1"),
                new ArtifactReference("artifact:input-manifest"),
                hash("2"),
                "workspace:session-1",
                List.of("inputs"),
                List.of("candidate"),
                Set.of(new WorkerCapability("repository-read")),
                "factory.pack-candidate",
                "1.0.0",
                new ArtifactReference("artifact:policy"),
                deadline,
                3,
                LOGICAL_WORK_ORDER_KEY,
                WorkOrderCreatorType.SYSTEM,
                "factory-builder",
                now.minusSeconds(60));
        orders.create(workOrder);
        String operationId = WorkerDispatchOperationIds.derive(
                TENANT, WORK_ORDER, WORKER_RUN, 1, "coding-worker");
        WorkerRunRecord workerRun = new WorkerRunRecord(
                WORKER_RUN,
                TENANT,
                SESSION,
                WORK_ORDER,
                1,
                "coding-worker",
                "1.0.0",
                new WorkerCapabilities(Set.of(new WorkerCapability("repository-read"))),
                WorkerRunStatus.REQUESTED,
                Optional.empty(),
                operationId,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                deadline,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                now.minusSeconds(60),
                now.minusSeconds(60));
        runs.create(workerRun);
    }

    private static ActionProposal proposal() {
        return ActionProposal.builder(WorkerDispatchAction.ACTION_ID)
                .proposalId("proposal-1")
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("factory-builder")
                .input(Map.of(
                        WorkerDispatchAction.WORK_ORDER_ID, WORK_ORDER.value(),
                        WorkerDispatchAction.WORKER_RUN_ID, WORKER_RUN.value(),
                        WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION, 0L))
                .idempotencyKey(WorkerDispatchIdempotencyKeys.derive(
                        TENANT, WORK_ORDER, LOGICAL_WORK_ORDER_KEY, WORKER_RUN, 1))
                .build();
    }

    private static ExecutionContext executionContext(String runId, boolean permission) {
        return new ExecutionContext(
                TENANT.value(),
                "factory-builder",
                runId,
                "trace-" + runId,
                Map.of(
                        "actor.permissions",
                        permission ? Set.of(WorkerDispatchAction.PERMISSION) : Set.of(),
                        "resource.type",
                        WorkerDispatchAction.RESOURCE_TYPE,
                        "resource.id",
                        WORK_ORDER.value()));
    }

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CyclicBarrier start = new CyclicBarrier(tasks.size());
        try (var executor = Executors.newFixedThreadPool(tasks.size())) {
            java.util.ArrayList<Future<T>> futures = new java.util.ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return task.call();
                    }));
            }
            java.util.ArrayList<T> results = new java.util.ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return List.copyOf(results);
        }
    }

    private static long count(DataSource dataSource, String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static List<AuditRow> auditRows(DataSource dataSource, String runId) throws Exception {
        String sql = """
                SELECT event_id, event_type, tenant_id, action_id, run_id, trace_id, payload_json
                FROM action_audit
                WHERE run_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.ArrayList<AuditRow> rows = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    rows.add(new AuditRow(
                            resultSet.getString("event_id"),
                            resultSet.getString("event_type"),
                            resultSet.getString("tenant_id"),
                            resultSet.getString("action_id"),
                            resultSet.getString("run_id"),
                            resultSet.getString("trace_id"),
                            resultSet.getString("payload_json")));
                }
                return List.copyOf(rows);
            }
        }
    }

    private static Set<String> eventTypes(List<AuditRow> rows) {
        return rows.stream().map(AuditRow::eventType).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private record AuditRow(
            String eventId,
            String eventType,
            String tenantId,
            String actionId,
            String runId,
            String traceId,
            String payloadJson) {
        boolean hasScope(String expectedRunId, String expectedTraceId) {
            return TENANT.value().equals(tenantId)
                    && WorkerDispatchAction.ACTION_ID.equals(actionId)
                    && expectedRunId.equals(runId)
                    && expectedTraceId.equals(traceId);
        }
    }

    private static ContentHash hash(String digit) {
        return new ContentHash(digit.repeat(64));
    }
}
