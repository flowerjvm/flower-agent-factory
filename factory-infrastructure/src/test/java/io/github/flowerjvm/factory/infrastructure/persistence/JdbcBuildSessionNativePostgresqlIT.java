package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchActionExecutor;
import io.github.flowerjvm.factory.application.action.WorkerDispatchActionValidator;
import io.github.flowerjvm.factory.application.action.WorkerDispatchIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.action.WorkerDispatchPolicyGate;
import io.github.flowerjvm.factory.application.action.WorkerDispatchPreExecutionGuard;
import io.github.flowerjvm.factory.application.action.WorkerDispatchTransaction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.audit.TraceSink;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionDecision;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionDecisionType;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateVisibilityScopeResolver;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcDuplicateActionPolicy;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.JdbcRunStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcBuildSessionNativePostgresqlIT {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void nativePostgresqlMigrationAndSeparateConnectionCasRace() throws Exception {
        PGSimpleDataSource dataSource = dataSource();
        FactoryDatabaseMigrations.migrate(dataSource);

        JdbcBuildSessionContention.assertOneCasWinner(dataSource, "postgresql", 8);
    }

    @Test
    void nativePostgresqlCertificationLedgerEnforcesExactFkAndOneVersionCasWinner()
            throws Exception {
        PGSimpleDataSource dataSource = dataSource();
        JdbcCertificationRepositoryTest.Fixture fixture =
                JdbcCertificationRepositoryTest.Fixture.create(
                        dataSource, "native-postgresql-certification");

        fixture.certifications().create(fixture.requested());
        var certified = fixture.certified();
        assertTrue(fixture.certifications().compareAndSet(fixture.requested(), certified));
        assertFalse(fixture.certifications().compareAndSet(fixture.requested(), certified));
        assertEquals(
                certified,
                fixture.certifications()
                        .find(fixture.tenant(), fixture.certificationId())
                        .orElseThrow());

        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "UPDATE factory_certification SET candidate_hash = ?"
                                + " WHERE certification_id = ?")) {
            statement.setString(1, "f".repeat(64));
            statement.setString(2, fixture.certificationId().value());
            assertThrows(java.sql.SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void nativePostgresqlAllowsOneActiveBuildSessionPerRequestAndReplacementAfterTerminal()
            throws Exception {
        PGSimpleDataSource dataSource = dataSource();
        FactoryDatabaseMigrations.migrate(dataSource);

        JdbcLedgerOwnershipConstraints.assertOneActiveBuildSessionPerRequest(
                dataSource, "postgresql");
    }

    @Test
    void nativePostgresqlAllowsOneActiveWorkerAttemptAndReplacementAfterTerminal()
            throws Exception {
        PGSimpleDataSource dataSource = dataSource();
        FactoryDatabaseMigrations.migrate(dataSource);

        JdbcLedgerOwnershipConstraints.assertOneActiveWorkerAttemptPerWorkOrder(
                dataSource, "postgresql");
    }

    @Test
    void nativePostgresqlRejectsWorkerRunWithMismatchedSessionAndWorkOrder() {
        PGSimpleDataSource dataSource = dataSource();
        FactoryDatabaseMigrations.migrate(dataSource);

        JdbcLedgerOwnershipConstraints.assertWorkerRunCannotCrossSessionAndWorkOrder(
                dataSource, "postgresql");
    }

    @Test
    void nativePostgresqlRequiresExistingTenantScopedWorkOrderLineage() {
        PGSimpleDataSource dataSource = dataSource();
        FactoryDatabaseMigrations.migrate(dataSource);

        JdbcLedgerOwnershipConstraints.assertSupersededWorkOrderMustExistInTenant(
                dataSource, "postgresql");
    }

    @Test
    void nativePostgresqlBindsDecisionsAndTerminalReferencesToPointAndHash() {
        PGSimpleDataSource dataSource = dataSource();
        FactoryDatabaseMigrations.migrate(dataSource);

        JdbcLedgerOwnershipConstraints.assertDecisionReferencesArePointAndHashBound(
                dataSource, "postgresql");
    }

    @Test
    void nativePostgresqlSerializesDispatchAgainstSessionChangesAndRollsBackAtomically()
            throws Exception {
        PGSimpleDataSource dataSource = dataSource();
        FactoryDatabaseMigrations.migrate(dataSource);

        JdbcWorkerDispatchTransactionConcurrency.assertPhaseChangeBeforeDispatchPreventsOutbox(
                dataSource, "postgresql");
        JdbcWorkerDispatchTransactionConcurrency.assertCancellationBeforeDispatchPreventsOutbox(
                dataSource, "postgresql");
        JdbcWorkerDispatchTransactionConcurrency.assertBindingChangeBeforeDispatchPreventsOutbox(
                dataSource, "postgresql");
        JdbcWorkerDispatchTransactionConcurrency.assertDispatchBeforeCancellationSerializes(
                dataSource, "postgresql");
        JdbcWorkerDispatchTransactionConcurrency.assertDeadlineElapsedWhileWaitingPreventsOutbox(
                dataSource, "postgresql");
        JdbcWorkerDispatchTransactionConcurrency.assertOutboxFailureRollsBackWorkerRun(
                dataSource, "postgresql");
    }

    @Test
    void nativePostgresqlFullPipelineDuplicateRaceClaimsOneWorkerRunAndCreatesOneOutbox()
            throws Exception {
        PGSimpleDataSource dataSource = dataSource();
        FactoryDatabaseMigrations.migrate(dataSource);
        String prefix = "postgresql-pipeline-race";
        var buildSession = PersistenceFixtures.buildSession(
                prefix + "-session",
                prefix + "-request",
                BuildSessionPhase.GENERATE_CANDIDATE);
        var workOrder = PersistenceFixtures.workOrder(buildSession, prefix + "-order");
        String workerSuffix = prefix + "-worker";
        WorkerRunId workerRunId = new WorkerRunId("worker-" + workerSuffix);
        String operationId = WorkerDispatchOperationIds.derive(
                buildSession.tenantId(), workOrder.workOrderId(), workerRunId, 1, "coding-binding");
        var workerRun = PersistenceFixtures.workerRun(
                buildSession, workOrder, workerSuffix, 1, operationId);
        assertTrue(workerRun.actionRunId().isEmpty());
        assertTrue(workerRun.attemptTokenHash().isEmpty());
        new JdbcBuildSessionRepository(dataSource).create(buildSession);
        new JdbcWorkOrderRepository(dataSource).create(workOrder);
        new JdbcWorkerRunRepository(dataSource).create(workerRun);

        CountDownLatch losingDuplicateDecided = new CountDownLatch(1);
        AtomicInteger acceptedReservations = new AtomicInteger();
        AtomicInteger transactionInvocations = new AtomicInteger();
        WorkerDispatchTransaction delegate = new JdbcWorkerDispatchTransaction(
                dataSource,
                new ObjectMapper(),
                Clock.fixed(PersistenceFixtures.NOW.plusSeconds(1), ZoneOffset.UTC));
        WorkerDispatchTransaction blockingTransaction = (claimedWorkOrder, expected, dispatching, outbox) -> {
            transactionInvocations.incrementAndGet();
            try {
                if (!losingDuplicateDecided.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("losing duplicate did not decide while winner was paused");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while coordinating duplicate race", exception);
            }
            return delegate.prepare(claimedWorkOrder, expected, dispatching, outbox);
        };
        DefaultActionRuntime firstRuntime = workerDispatchRuntime(
                dataSource,
                blockingTransaction,
                acceptedReservations,
                losingDuplicateDecided);
        DefaultActionRuntime secondRuntime = workerDispatchRuntime(
                dataSource,
                blockingTransaction,
                acceptedReservations,
                losingDuplicateDecided);
        String idempotencyKey = WorkerDispatchIdempotencyKeys.derive(workOrder, workerRun);
        ActionProposal firstProposal = workerDispatchProposal(
                prefix + "-proposal-a", idempotencyKey, workOrder.workOrderId().value(), workerRunId.value());
        ActionProposal secondProposal = workerDispatchProposal(
                prefix + "-proposal-b", idempotencyKey, workOrder.workOrderId().value(), workerRunId.value());

        String firstRunId = prefix + "-action-run-a";
        String secondRunId = prefix + "-action-run-b";
        List<PipelineOutcome> outcomes = runConcurrently(List.of(
                () -> new PipelineOutcome(
                        firstRunId,
                        firstRuntime.handle(firstProposal, workerDispatchContext(
                                firstRunId, workOrder.workOrderId().value()))),
                () -> new PipelineOutcome(
                        secondRunId,
                        secondRuntime.handle(secondProposal, workerDispatchContext(
                                secondRunId, workOrder.workOrderId().value())))));

        assertEquals(
                1,
                outcomes.stream()
                        .filter(outcome -> outcome.result().status() == ActionExecutionStatus.ACCEPTED)
                        .count());
        assertEquals(1, acceptedReservations.get());
        assertEquals(1, transactionInvocations.get());
        var stored = new JdbcWorkerRunRepository(dataSource)
                .find(workerRun.tenantId(), workerRun.workerRunId())
                .orElseThrow();
        assertEquals(WorkerRunStatus.DISPATCHING, stored.status());
        assertEquals(1, stored.version());
        PipelineOutcome accepted = outcomes.stream()
                .filter(outcome -> outcome.result().status() == ActionExecutionStatus.ACCEPTED)
                .findFirst()
                .orElseThrow();
        assertEquals(accepted.runId(), stored.actionRunId().orElseThrow());
        assertTrue(stored.attemptTokenHash().isPresent());
        assertTrue(stored.dispatchOutboxId().isPresent());
        assertTrue(stored.startedAt().isPresent());
        assertEquals(1L, countRows(dataSource, "factory_dispatch_outbox"));
        assertEquals(2L, countRows(dataSource, "action_run"));

        String ownerRunId = stored.actionRunId().orElseThrow();
        var ownerRun = new JdbcRunStore(dataSource, new ObjectMapper())
                .find(ownerRunId)
                .orElseThrow();
        ActionExecutionResult canonical = ActionExecutionResult.succeeded(Map.of(
                "workerRunId", stored.workerRunId().value(),
                "result", "canonical"));
        DuplicateActionPolicy observedRaceDelegate = observedDuplicatePolicy(
                dataSource,
                acceptedReservations,
                losingDuplicateDecided);
        TransitionBarrierDuplicatePolicy transitionBarrierPolicy =
                new TransitionBarrierDuplicatePolicy(observedRaceDelegate);
        DefaultActionRuntime completionRuntime = workerDispatchRuntime(
                dataSource,
                blockingTransaction,
                transitionBarrierPolicy);
        DefaultActionRuntime racingReplayRuntime = workerDispatchRuntime(
                dataSource,
                blockingTransaction,
                transitionBarrierPolicy);
        ActionProposal racingReplayProposal = workerDispatchProposal(
                prefix + "-proposal-racing-replay",
                idempotencyKey,
                workOrder.workOrderId().value(),
                workerRunId.value());
        List<ActionExecutionResult> completionRace = runConcurrently(List.of(
                () -> completionRuntime.complete(ownerRunId, ownerRun.attemptToken(), canonical),
                () -> racingReplayRuntime.handle(
                        racingReplayProposal,
                        workerDispatchContext(
                                prefix + "-action-run-racing-replay",
                                workOrder.workOrderId().value()))));
        assertEquals(2, transitionBarrierPolicy.transitionEntrants());
        assertEquals(canonical, completionRace.getFirst());
        ActionExecutionResult racingReplay = completionRace.get(1);
        if (!canonical.equals(racingReplay)) {
            assertEquals(ActionExecutionStatus.DENIED, racingReplay.status());
            assertEquals("DUPLICATE_ACTION", racingReplay.code());
        }

        DefaultActionRuntime lateReplayRuntime = workerDispatchRuntime(
                dataSource,
                blockingTransaction,
                acceptedReservations,
                losingDuplicateDecided);
        ActionProposal lateReplayProposal = workerDispatchProposal(
                prefix + "-proposal-late-replay",
                idempotencyKey,
                workOrder.workOrderId().value(),
                workerRunId.value());
        ActionExecutionResult lateReplay = lateReplayRuntime.handle(
                lateReplayProposal,
                workerDispatchContext(prefix + "-action-run-late-replay", workOrder.workOrderId().value()));

        assertEquals(canonical, lateReplay);
        assertEquals(1, acceptedReservations.get());
        assertEquals(1, transactionInvocations.get());
        assertEquals(
                stored,
                new JdbcWorkerRunRepository(dataSource)
                        .find(workerRun.tenantId(), workerRun.workerRunId())
                        .orElseThrow());
        assertEquals(1L, countRows(dataSource, "factory_dispatch_outbox"));
        assertEquals(4L, countRows(dataSource, "action_run"));
    }

    @Test
    void nativePostgresqlDuplicateReservationRestartFirstResultAndAbaContract() throws Exception {
        PGSimpleDataSource dataSource = dataSource();
        FactoryDatabaseMigrations.migrate(dataSource);
        List<Reservation> reservations = runConcurrently(java.util.stream.IntStream.range(0, 16)
                .mapToObj(index -> (Callable<Reservation>) () -> {
                    ActionProposal proposal = proposal("native-concurrent-" + index, "native-concurrent-key");
                    ExecutionContext context = context("native-concurrent-run-" + index);
                    DuplicateActionDecision decision = JdbcDuplicateActionPolicy.create(dataSource)
                            .reserve(proposal, context);
                    return new Reservation(proposal, context, decision.type());
                })
                .toList());
        List<Reservation> accepted = reservations.stream()
                .filter(reservation -> reservation.type() == DuplicateActionDecisionType.ACCEPT)
                .toList();
        assertEquals(1, accepted.size());
        assertEquals(15, reservations.stream()
                .filter(reservation -> reservation.type() == DuplicateActionDecisionType.REJECT)
                .count());

        Reservation winner = accepted.getFirst();
        assertEquals(
                DuplicateActionDecisionType.REJECT,
                JdbcDuplicateActionPolicy.create(dataSource)
                        .reserve(winner.proposal(), winner.context())
                        .type());
        ActionExecutionResult canonical = ActionExecutionResult.succeeded(Map.of("owner", winner.context().runId()));
        JdbcDuplicateActionPolicy.create(dataSource)
                .complete(winner.proposal(), winner.context(), canonical);
        assertEquals(
                canonical,
                JdbcDuplicateActionPolicy.create(dataSource)
                        .reserve(proposal("native-restart", "native-concurrent-key"), context("native-restart-run"))
                        .existingResult());

        for (int iteration = 0; iteration < 5; iteration++) {
            String key = "native-aba-key-" + iteration;
            ActionProposal proposalA = proposal("native-aba-a-" + iteration, key);
            ActionProposal proposalB = proposal("native-aba-b-" + iteration, key);
            ExecutionContext ownerA = context("native-aba-run-a-" + iteration);
            ExecutionContext ownerB = context("native-aba-run-b-" + iteration);
            JdbcDuplicateActionPolicy policyA = JdbcDuplicateActionPolicy.create(dataSource);
            JdbcDuplicateActionPolicy policyB = JdbcDuplicateActionPolicy.create(dataSource);
            assertEquals(DuplicateActionDecisionType.ACCEPT, policyA.reserve(proposalA, ownerA).type());
            policyA.release(proposalA, ownerA, new IllegalStateException("known pre-dispatch failure"));

            List<Object> outcomes = runConcurrently(List.of(
                    () -> policyB.reserve(proposalB, ownerB),
                    () -> {
                        policyA.complete(
                                proposalA,
                                ownerA,
                                ActionExecutionResult.succeeded(Map.of("owner", "stale-a")));
                        return Boolean.TRUE;
                    },
                    () -> {
                        policyA.release(proposalA, ownerA, new IllegalStateException("delayed stale release"));
                        return Boolean.TRUE;
                    }));
            assertEquals(
                    DuplicateActionDecisionType.ACCEPT,
                    ((DuplicateActionDecision) outcomes.getFirst()).type());
            assertEquals(
                    DuplicateActionDecisionType.REJECT,
                    JdbcDuplicateActionPolicy.create(dataSource)
                            .reserve(proposal("native-aba-c-" + iteration, key), context("native-aba-run-c-" + iteration))
                            .type());

            ActionExecutionResult ownerBResult = ActionExecutionResult.succeeded(Map.of("owner", "b"));
            policyB.complete(proposalB, ownerB, ownerBResult);
            policyB.complete(
                    proposalB,
                    ownerB,
                    ActionExecutionResult.succeeded(Map.of("owner", "replacement")));
            policyA.complete(
                    proposalA,
                    ownerA,
                    ActionExecutionResult.succeeded(Map.of("owner", "stale-a")));
            assertEquals(
                    ownerBResult,
                    JdbcDuplicateActionPolicy.create(dataSource)
                            .reserve(proposal("native-aba-d-" + iteration, key), context("native-aba-run-d-" + iteration))
                            .existingResult());
        }
    }

    @Test
    void nativePostgresqlResourceVisibilityRetryableCanonicalResultAndNoTtlTakeover() throws Exception {
        PGSimpleDataSource dataSource = dataSource();
        FactoryDatabaseMigrations.migrate(dataSource);

        Map<String, String> trustedResourceIndex = Map.of(
                "work-a", "work-order:canonical-a",
                "work-b", "work-order:canonical-b");
        DuplicateVisibilityScopeResolver trustedResolver = (proposal, context) -> {
            Object requested = proposal.input().get("workOrderId");
            String canonical = trustedResourceIndex.get(String.valueOf(requested));
            if (canonical == null) {
                throw new IllegalArgumentException("unknown trusted work order");
            }
            return canonical;
        };
        JdbcDuplicateActionPolicy resourcePolicy =
                JdbcDuplicateActionPolicy.create(dataSource, trustedResolver);
        ActionProposal resourceA = proposal(
                "native-resource-a", "native-shared-resource-key", Map.of("workOrderId", "work-a"));
        ActionProposal resourceB = proposal(
                "native-resource-b", "native-shared-resource-key", Map.of("workOrderId", "work-b"));
        ExecutionContext resourceAOwner = context("native-resource-run-a");
        ExecutionContext resourceBOwner = context("native-resource-run-b");
        assertEquals(DuplicateActionDecisionType.ACCEPT, resourcePolicy.reserve(resourceA, resourceAOwner).type());
        assertEquals(DuplicateActionDecisionType.ACCEPT, resourcePolicy.reserve(resourceB, resourceBOwner).type());
        ActionExecutionResult resourceAResult = ActionExecutionResult.succeeded(Map.of("resource", "a"));
        ActionExecutionResult resourceBResult = ActionExecutionResult.succeeded(Map.of("resource", "b"));
        resourcePolicy.complete(resourceA, resourceAOwner, resourceAResult);
        resourcePolicy.complete(resourceB, resourceBOwner, resourceBResult);
        assertEquals(
                resourceAResult,
                resourcePolicy
                        .reserve(
                                proposal("native-resource-a-later", "native-shared-resource-key", Map.of("workOrderId", "work-a")),
                                context("native-resource-run-a-later"))
                        .existingResult());
        assertEquals(
                resourceBResult,
                resourcePolicy
                        .reserve(
                                proposal("native-resource-b-later", "native-shared-resource-key", Map.of("workOrderId", "work-b")),
                                context("native-resource-run-b-later"))
                        .existingResult());

        JdbcDuplicateActionPolicy retryPolicy = JdbcDuplicateActionPolicy.create(dataSource);
        ActionProposal retryAttempt = proposal("native-retry-original", "native-retry-key");
        ExecutionContext retryOwner = context("native-retry-run-original");
        assertEquals(DuplicateActionDecisionType.ACCEPT, retryPolicy.reserve(retryAttempt, retryOwner).type());
        ActionExecutionResult retryable =
                ActionExecutionResult.retryableFailure("TEMPORARY_WORKER_FAILURE", "try a new governed attempt");
        retryPolicy.complete(retryAttempt, retryOwner, retryable);
        assertEquals(
                retryable,
                retryPolicy
                        .reserve(proposal("native-retry-redelivery", "native-retry-key"), context("native-retry-run-redelivery"))
                        .existingResult());
        assertEquals(
                DuplicateActionDecisionType.ACCEPT,
                retryPolicy
                        .reserve(proposal("native-retry-new-attempt", "native-retry-key-new-attempt"), context("native-retry-run-new-attempt"))
                        .type());

        ActionProposal oldRunning = proposal("native-old-running", "native-old-running-key");
        ExecutionContext oldOwner = context("native-old-running-owner");
        assertEquals(DuplicateActionDecisionType.ACCEPT, retryPolicy.reserve(oldRunning, oldOwner).type());
        ageReservation(dataSource, oldOwner.runId());
        assertEquals(
                DuplicateActionDecisionType.REJECT,
                JdbcDuplicateActionPolicy.create(dataSource)
                        .reserve(
                                proposal("native-old-running-takeover", "native-old-running-key"),
                                context("native-old-running-new-owner"))
                        .type());
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private static DefaultActionRuntime workerDispatchRuntime(
            PGSimpleDataSource dataSource,
            WorkerDispatchTransaction transaction,
            AtomicInteger acceptedReservations,
            CountDownLatch losingDuplicateDecided) {
        return workerDispatchRuntime(
                dataSource,
                transaction,
                observedDuplicatePolicy(dataSource, acceptedReservations, losingDuplicateDecided));
    }

    private static DuplicateActionPolicy observedDuplicatePolicy(
            PGSimpleDataSource dataSource,
            AtomicInteger acceptedReservations,
            CountDownLatch losingDuplicateDecided) {
        ObjectMapper objectMapper = new ObjectMapper();
        var workOrders = new JdbcWorkOrderRepository(dataSource, objectMapper);
        return new ObservedDuplicatePolicy(
                new JdbcDuplicateActionPolicy(
                        dataSource,
                        objectMapper,
                        new WorkerDispatchVisibilityScopeResolver(workOrders)),
                acceptedReservations,
                losingDuplicateDecided);
    }

    private static DefaultActionRuntime workerDispatchRuntime(
            PGSimpleDataSource dataSource,
            WorkerDispatchTransaction transaction,
            DuplicateActionPolicy duplicatePolicy) {
        ObjectMapper objectMapper = new ObjectMapper();
        var workOrders = new JdbcWorkOrderRepository(dataSource, objectMapper);
        var workerRuns = new JdbcWorkerRunRepository(dataSource, objectMapper);
        var buildSessions = new JdbcBuildSessionRepository(dataSource);
        var executor = new WorkerDispatchActionExecutor(
                workOrders,
                workerRuns,
                transaction,
                Clock.fixed(PersistenceFixtures.NOW.plusSeconds(1), ZoneOffset.UTC));
        return new DefaultActionRuntime(
                new InMemoryActionRegistry(List.of(executor)),
                new WorkerDispatchActionValidator(),
                new WorkerDispatchPolicyGate(workOrders, workerRuns),
                (proposal, definition, context, policyDecision) -> {
                    throw new IllegalStateException("worker dispatch must not request approval");
                },
                duplicatePolicy,
                new JdbcActionAuditSink(dataSource, objectMapper),
                TraceSink.noop(),
                new JdbcRunStore(dataSource, objectMapper),
                new WorkerDispatchPreExecutionGuard(
                        workOrders,
                        workerRuns,
                        buildSessions,
                        Clock.fixed(PersistenceFixtures.NOW.plusSeconds(1), ZoneOffset.UTC)));
    }

    private static ActionProposal workerDispatchProposal(
            String proposalId,
            String idempotencyKey,
            String workOrderId,
            String workerRunId) {
        return ActionProposal.builder(WorkerDispatchAction.ACTION_ID)
                .proposalId(proposalId)
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("factory-service")
                .input(Map.of(
                        WorkerDispatchAction.WORK_ORDER_ID, workOrderId,
                        WorkerDispatchAction.WORKER_RUN_ID, workerRunId,
                        WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION, 0L))
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private static ExecutionContext workerDispatchContext(String runId, String workOrderId) {
        return new ExecutionContext(
                PersistenceFixtures.TENANT.value(),
                "factory-service",
                runId,
                "trace-" + runId,
                Map.of(
                        "actor.permissions", Set.of(WorkerDispatchAction.PERMISSION),
                        "resource.type", WorkerDispatchAction.RESOURCE_TYPE,
                        "resource.id", workOrderId));
    }

    private static long countRows(PGSimpleDataSource dataSource, String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static ActionProposal proposal(String proposalId, String idempotencyKey) {
        return proposal(proposalId, idempotencyKey, Map.of());
    }

    private static ActionProposal proposal(
            String proposalId, String idempotencyKey, Map<String, Object> input) {
        return ActionProposal.builder("factory.worker.dispatch")
                .proposalId(proposalId)
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("factory-service")
                .input(input)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private static ExecutionContext context(String runId) {
        return new ExecutionContext("tenant-native", "service-principal", runId, runId + "-trace", Map.of());
    }

    private static <T> List<T> runConcurrently(List<? extends Callable<? extends T>> tasks) throws Exception {
        CyclicBarrier start = new CyclicBarrier(tasks.size());
        try (var executor = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<? extends T>> futures = new ArrayList<>();
            for (Callable<? extends T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<? extends T> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return List.copyOf(results);
        }
    }

    private static void ageReservation(PGSimpleDataSource dataSource, String ownerRunId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE action_duplicate SET created_at = 0, updated_at = 0
                        WHERE owner_run_id = ? AND state = 'RUNNING'
                        """)) {
            statement.setString(1, ownerRunId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private record Reservation(
            ActionProposal proposal,
            ExecutionContext context,
            DuplicateActionDecisionType type) {}

    private record PipelineOutcome(String runId, ActionExecutionResult result) {}

    private static final class ObservedDuplicatePolicy implements DuplicateActionPolicy {
        private final DuplicateActionPolicy delegate;
        private final AtomicInteger acceptedReservations;
        private final CountDownLatch losingDuplicateDecided;

        private ObservedDuplicatePolicy(
                DuplicateActionPolicy delegate,
                AtomicInteger acceptedReservations,
                CountDownLatch losingDuplicateDecided) {
            this.delegate = delegate;
            this.acceptedReservations = acceptedReservations;
            this.losingDuplicateDecided = losingDuplicateDecided;
        }

        @Override
        public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
            DuplicateActionDecision decision = delegate.reserve(proposal, context);
            if (decision.type() == DuplicateActionDecisionType.ACCEPT) {
                acceptedReservations.incrementAndGet();
            } else {
                losingDuplicateDecided.countDown();
            }
            return decision;
        }

        @Override
        public void complete(
                ActionProposal proposal,
                ExecutionContext context,
                ActionExecutionResult result) {
            delegate.complete(proposal, context, result);
        }

        @Override
        public void release(ActionProposal proposal, ExecutionContext context, Throwable cause) {
            delegate.release(proposal, context, cause);
        }
    }

    /** Meets reserve and complete immediately before their competing JDBC state transitions. */
    private static final class TransitionBarrierDuplicatePolicy implements DuplicateActionPolicy {
        private final DuplicateActionPolicy delegate;
        private final CyclicBarrier transitionBarrier = new CyclicBarrier(2);
        private final AtomicInteger transitionEntrants = new AtomicInteger();

        private TransitionBarrierDuplicatePolicy(DuplicateActionPolicy delegate) {
            this.delegate = delegate;
        }

        @Override
        public DuplicateActionDecision reserve(ActionProposal proposal, ExecutionContext context) {
            awaitTransition();
            return delegate.reserve(proposal, context);
        }

        @Override
        public void complete(
                ActionProposal proposal,
                ExecutionContext context,
                ActionExecutionResult result) {
            awaitTransition();
            delegate.complete(proposal, context, result);
        }

        @Override
        public void release(ActionProposal proposal, ExecutionContext context, Throwable cause) {
            delegate.release(proposal, context, cause);
        }

        int transitionEntrants() {
            return transitionEntrants.get();
        }

        private void awaitTransition() {
            transitionEntrants.incrementAndGet();
            try {
                transitionBarrier.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted at duplicate transition barrier", exception);
            } catch (Exception exception) {
                throw new IllegalStateException("duplicate transition barrier failed", exception);
            }
        }
    }
}
