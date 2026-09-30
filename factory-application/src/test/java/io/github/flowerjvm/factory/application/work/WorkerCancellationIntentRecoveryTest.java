package io.github.flowerjvm.factory.application.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.flow.FactoryFlowCancellationRecovery;
import io.github.flowerjvm.factory.application.flow.FactoryFlowCancellationService;
import io.github.flowerjvm.factory.application.flow.CreateCustomerAgentFlowFactory;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineRegistry;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxRepository;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import io.github.flowerjvm.flower.core.engine.Engine;
import io.github.flowerjvm.flower.core.worker.Worker;
import io.github.flowerjvm.flower.core.step.StepResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkerCancellationIntentRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC);
    private static final TenantId TENANT = new TenantId("tenant-cancel-recovery");
    private static final BuildSessionId SESSION = new BuildSessionId("session-cancel-recovery");
    private static final WorkOrderId ORDER = new WorkOrderId("order-cancel-recovery");
    private static final WorkerRunId RUN = new WorkerRunId("run-cancel-recovery");
    private static final String BINDING = "codex-worker";
    private static final String ACTION = "action-cancel-recovery";
    private static final String TOKEN = "attempt-cancel-recovery";
    private static final String OPERATION = WorkerDispatchOperationIds.derive(
            TENANT, ORDER, RUN, 1, BINDING);

    @Test
    void requesterRestagesIntentAfterCancelHookPreCommitFailure() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL, waitingRun());
        CompletableActionRuntime runtime = fixture.runtimeThatCancelsActionWithoutHookCommit();
        WorkerCancellationRequester requester = new WorkerCancellationRequester(
                fixture.workerRuns, runtime, fixture.recovery);

        WorkerCancellationRequestDisposition disposition = requester.request(TENANT, SESSION);

        assertEquals(WorkerCancellationRequestDisposition.CANCEL_OUTBOX_STAGED, disposition);
        assertEquals(WorkerRunStatus.CANCEL_REQUESTED, fixture.workerRuns.current().status());
        assertEquals(1, fixture.transactionCalls.get());
        DispatchOutbox outbox = fixture.outboxes.current().orElseThrow();
        assertEquals(DispatchOutboxStatus.PENDING, outbox.status());
        assertEquals(WorkerOutboxOperations.CANCEL, outbox.operationType());
        assertEquals(
                WorkerDispatchOperationIds.cancelOutboxId(OPERATION),
                outbox.outboxId().value());
    }

    @Test
    void startupScanRestagesMissingIntentForCanonicalCancelledAction() {
        Fixture fixture = new Fixture(ActionRunStatus.CANCELLED, dispatchingRun());

        assertTrue(fixture.recovery.tickOnce());

        assertEquals(WorkerRunStatus.CANCEL_REQUESTED, fixture.workerRuns.current().status());
        assertEquals(1, fixture.transactionCalls.get());
        assertEquals(DispatchOutboxStatus.PENDING, fixture.outboxes.current().orElseThrow().status());
        assertEquals(false, fixture.recovery.tickOnce());
        assertEquals(1, fixture.transactionCalls.get());
    }

    @Test
    void cancellingSessionCrashBeforeRequesterIsResumedByBoundedGovernedRecovery() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL, waitingRun());
        WorkerCancellationRequester requester = new WorkerCancellationRequester(
                fixture.workerRuns,
                fixture.runtimeThatCancelsActionWithoutHookCommit(),
                fixture.recovery);
        MemorySessions sessions = new MemorySessions(
                runningSession().requestCancellation(NOW.plusSeconds(2)));
        FactoryFlowCancellationService cancellation = new FactoryFlowCancellationService(
                sessions,
                Engine.builder().worker(Worker.builder("cancel-recovery").build()).build(),
                productLines(),
                requester,
                CLOCK);
        FactoryFlowCancellationRecovery recovery = new FactoryFlowCancellationRecovery(
                sessions, fixture.workerRuns, cancellation, CLOCK, 8);

        assertTrue(recovery.tickOnce());

        assertEquals(BuildSessionStatus.CANCELLING, sessions.current().status());
        assertEquals(WorkerRunStatus.CANCEL_REQUESTED, fixture.workerRuns.current().status());
        assertEquals(ActionRunStatus.CANCELLED,
                fixture.actions.find(ACTION).orElseThrow().status());
        assertEquals(1, fixture.transactionCalls.get());
        assertEquals(DispatchOutboxStatus.PENDING, fixture.outboxes.current().orElseThrow().status());
    }

    @Test
    void preDispatchCancellingSessionWithoutActiveWorkerIsConfirmedAfterRestart() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL, waitingRun());
        WorkerRunRepository noActiveWorker = new WorkerRunRepository() {
            @Override public void create(WorkerRunRecord workerRun) {
                throw new UnsupportedOperationException();
            }
            @Override public Optional<WorkerRunRecord> find(
                    TenantId tenantId, WorkerRunId workerRunId) {
                return Optional.empty();
            }
            @Override public Optional<WorkerRunRecord> findActiveByBuildSession(
                    TenantId tenantId, BuildSessionId buildSessionId) {
                return Optional.empty();
            }
            @Override public boolean compareAndSet(
                    WorkerRunRecord expected, WorkerRunRecord next) {
                return false;
            }
        };
        WorkerCancellationRequester requester = new WorkerCancellationRequester(
                noActiveWorker,
                fixture.runtimeThatCancelsActionWithoutHookCommit(),
                fixture.recovery);
        MemorySessions sessions = new MemorySessions(
                runningSession().requestCancellation(NOW.plusSeconds(2)));
        FactoryFlowCancellationService cancellation = new FactoryFlowCancellationService(
                sessions,
                Engine.builder().worker(Worker.builder("cancel-no-effect-recovery").build()).build(),
                productLines(),
                requester,
                CLOCK);
        FactoryFlowCancellationRecovery recovery = new FactoryFlowCancellationRecovery(
                sessions, noActiveWorker, cancellation, CLOCK, 8);

        assertTrue(recovery.tickOnce());

        assertEquals(BuildSessionStatus.CANCELLED, sessions.current().status());
        assertEquals(
                Optional.of("BUILD_SESSION_CANCELLED_BEFORE_EXTERNAL_EFFECT"),
                sessions.current().terminalCode());
        assertEquals(0, fixture.transactionCalls.get());
    }

    @Test
    void unregisteredProductLineFailsBeforeSessionOrWorkerCancellationMutation() {
        Fixture fixture = new Fixture(ActionRunStatus.WAITING_EXTERNAL, waitingRun());
        WorkerCancellationRequester requester = new WorkerCancellationRequester(
                fixture.workerRuns,
                fixture.runtimeThatCancelsActionWithoutHookCommit(),
                fixture.recovery);
        MemorySessions sessions = new MemorySessions(
                withProductLine(runningSession(), new ProductLineId("unregistered-line")));
        FactoryFlowCancellationService cancellation = new FactoryFlowCancellationService(
                sessions,
                Engine.builder().worker(Worker.builder("cancel-unknown-line").build()).build(),
                productLines(),
                requester,
                CLOCK);

        assertThrows(IllegalArgumentException.class, () -> cancellation.cancel(TENANT, SESSION));

        assertEquals(BuildSessionStatus.RUNNING, sessions.current().status());
        assertEquals(WorkerRunStatus.WAITING_EXTERNAL, fixture.workerRuns.current().status());
        assertEquals(0, fixture.transactionCalls.get());
    }

    private static final class Fixture {
        private final MemoryWorkerRuns workerRuns;
        private final MemoryOutboxes outboxes = new MemoryOutboxes();
        private final InMemoryRunStore actions = actionStore();
        private final AtomicInteger transactionCalls = new AtomicInteger();
        private final WorkerCancellationIntentRecovery recovery;

        private Fixture(ActionRunStatus actionStatus, WorkerRunRecord initialRun) {
            workerRuns = new MemoryWorkerRuns(initialRun);
            ActionRun current = actions.find(ACTION).orElseThrow();
            if (actionStatus == ActionRunStatus.CANCELLED) {
                actions.compareAndSet(current, cancelled(current));
            }
            WorkerCancellationTransaction transaction = (order, expected, next, outbox) -> {
                transactionCalls.incrementAndGet();
                if (outboxes.current().isPresent() || !workerRuns.compareAndSet(expected, next)) {
                    return false;
                }
                outboxes.create(outbox);
                return true;
            };
            recovery = new WorkerCancellationIntentRecovery(
                    workOrders(), workerRuns, outboxes, actions, transaction, CLOCK, 8);
        }

        private CompletableActionRuntime runtimeThatCancelsActionWithoutHookCommit() {
            return new CompletableActionRuntime() {
                @Override public ActionExecutionResult handle(
                        ActionProposal proposal, ExecutionContext context) {
                    throw new UnsupportedOperationException();
                }
                @Override public ActionExecutionResult resume(
                        String runId, ApprovalDecision decision) {
                    throw new UnsupportedOperationException();
                }
                @Override public ActionExecutionResult complete(
                        String runId, String attemptToken, ActionExecutionResult result) {
                    throw new UnsupportedOperationException();
                }
                @Override public ActionExecutionResult cancel(String runId, String reason) {
                    ActionRun current = actions.find(runId).orElseThrow();
                    ActionRun terminal = cancelled(current);
                    if (!actions.compareAndSet(current, terminal)) {
                        throw new IllegalStateException("simulated Action cancellation CAS lost");
                    }
                    // Simulates Action Runtime 0.3.3 absorbing a failed deferred hook and then
                    // persisting its own canonical CANCELLED result.
                    return terminal.result();
                }
            };
        }
    }

    private static WorkOrderRepository workOrders() {
        WorkOrder order = workOrder();
        return new WorkOrderRepository() {
            @Override public void create(WorkOrder workOrder) { throw new UnsupportedOperationException(); }
            @Override public Optional<WorkOrder> find(TenantId tenantId, WorkOrderId workOrderId) {
                return TENANT.equals(tenantId) && ORDER.equals(workOrderId)
                        ? Optional.of(order) : Optional.empty();
            }
        };
    }

    private static FactoryProductLineRegistry productLines() {
        return new FactoryProductLineRegistry(List.of(
                new CreateCustomerAgentFlowFactory((phase, context) -> StepResult.stay())));
    }

    private static WorkOrder workOrder() {
        return new WorkOrder(
                ORDER, TENANT, SESSION, "generate-candidate", "generate", 1,
                Optional.empty(), Optional.empty(), Optional.empty(),
                new ArtifactReference("artifact:instruction"), hash("a"),
                new ArtifactReference("artifact:input"), hash("b"),
                "workspace:cancel-recovery", List.of("src"), List.of("src"), Set.of(),
                "factory.candidate.v1", "1", new ArtifactReference("artifact:policy"),
                NOW.plusSeconds(300), 1, "logical-cancel-recovery",
                WorkOrderCreatorType.SYSTEM, "test", NOW);
    }

    private static BuildSession runningSession() {
        return new BuildSession(
                SESSION,
                TENANT,
                new ProjectId("project-cancel-recovery"),
                ProductLineId.AGENT_PACK,
                "request-cancel-recovery",
                "test",
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.GENERATE_CANDIDATE,
                new ArtifactReference("artifact:requirements"),
                hash("c"),
                Optional.empty(),
                Optional.of(BINDING),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                3,
                NOW,
                NOW.plusSeconds(300),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                NOW);
    }

    private static BuildSession withProductLine(BuildSession source, ProductLineId productLineId) {
        return new BuildSession(
                source.buildSessionId(), source.tenantId(), source.projectId(), productLineId,
                source.requestIdempotencyKey(), source.createdBy(), source.status(), source.currentPhase(),
                source.requirementsArtifactRef(), source.requirementsHash(),
                source.selectedManagerWorkerBinding(), source.selectedCodingWorkerBinding(),
                source.currentBlueprintRef(), source.currentCandidateId(), source.currentCandidateHash(),
                source.currentCertificationId(), source.repairRound(), source.maxRepairRounds(),
                source.startedAt(), source.deadlineAt(), source.cancellationRequestedAt(),
                source.terminalCode(), source.terminalMessage(), source.version(),
                source.createdAt(), source.updatedAt());
    }

    private static WorkerRunRecord waitingRun() {
        return dispatchingRun().awaitExternal("worker-session", NOW.plusSeconds(2));
    }

    private static WorkerRunRecord dispatchingRun() {
        WorkerRunRecord requested = new WorkerRunRecord(
                RUN, TENANT, SESSION, ORDER, 1, BINDING, "1",
                new WorkerCapabilities(Set.of()), WorkerRunStatus.REQUESTED, Optional.empty(),
                OPERATION, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                NOW.plusSeconds(300), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW, NOW);
        return requested.startDispatch(
                new DispatchOutboxId("dispatch-cancel-recovery"), ACTION,
                WorkerDispatchOperationIds.hashAttemptToken(TOKEN), NOW.plusSeconds(1));
    }

    private static InMemoryRunStore actionStore() {
        InMemoryRunStore store = new InMemoryRunStore();
        store.create(ActionRun.builder()
                .runId(ACTION)
                .tenantId(TENANT.value())
                .actionId(WorkerDispatchAction.ACTION_ID)
                .input(Map.of(
                        WorkerDispatchAction.WORK_ORDER_ID, ORDER.value(),
                        WorkerDispatchAction.WORKER_RUN_ID, RUN.value(),
                        WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION, 0L))
                .status(ActionRunStatus.WAITING_EXTERNAL)
                .attemptToken(TOKEN)
                .externalOperationId(OPERATION)
                .dueAt(NOW.plusSeconds(300))
                .createdAt(NOW)
                .updatedAt(NOW.plusSeconds(2))
                .build());
        return store;
    }

    private static ActionRun cancelled(ActionRun current) {
        ActionExecutionResult result = ActionExecutionResult.cancelled(
                "ACTION_CANCELLED", WorkerCancellationRequester.REASON_CODE);
        return current.toBuilder()
                .version(current.version() + 1)
                .status(ActionRunStatus.CANCELLED)
                .result(result)
                .updatedAt(CLOCK.instant())
                .build();
    }

    private static ContentHash hash(String seed) {
        return new ContentHash(seed.repeat(64).substring(0, 64));
    }

    private static final class MemoryWorkerRuns implements WorkerRunRepository {
        private final AtomicReference<WorkerRunRecord> value;
        private MemoryWorkerRuns(WorkerRunRecord initial) { value = new AtomicReference<>(initial); }
        private WorkerRunRecord current() { return value.get(); }
        @Override public void create(WorkerRunRecord workerRun) { throw new UnsupportedOperationException(); }
        @Override public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId) {
            WorkerRunRecord current = value.get();
            return current.tenantId().equals(tenantId) && current.workerRunId().equals(workerRunId)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<WorkerRunRecord> findActiveByBuildSession(
                TenantId tenantId, BuildSessionId buildSessionId) {
            WorkerRunRecord current = value.get();
            return current.tenantId().equals(tenantId)
                            && current.buildSessionId().equals(buildSessionId)
                            && !current.status().isTerminal()
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public List<WorkerRunRecord> findActiveForReconciliation(
                Instant updatedBefore, int limit) {
            WorkerRunRecord current = value.get();
            return !current.status().isTerminal()
                            && !current.updatedAt().isAfter(updatedBefore)
                            && limit > 0
                    ? List.of(current) : List.of();
        }
        @Override public List<WorkerRunRecord> findCancellationRecoveryCandidates(
                Instant updatedBefore, int limit) {
            WorkerRunRecord current = value.get();
            return !current.status().isTerminal()
                            && !current.updatedAt().isAfter(updatedBefore)
                            && limit > 0
                    ? List.of(current) : List.of();
        }
        @Override public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
            return value.compareAndSet(expected, next);
        }
    }

    private static final class MemoryOutboxes implements DispatchOutboxRepository {
        private final AtomicReference<DispatchOutbox> value = new AtomicReference<>();
        private Optional<DispatchOutbox> current() { return Optional.ofNullable(value.get()); }
        @Override public void create(DispatchOutbox outbox) {
            if (!value.compareAndSet(null, outbox)) {
                throw new IllegalStateException("duplicate outbox");
            }
        }
        @Override public Optional<DispatchOutbox> find(TenantId tenantId, DispatchOutboxId outboxId) {
            return current().filter(outbox -> outbox.tenantId().equals(tenantId)
                    && outbox.outboxId().equals(outboxId));
        }
        @Override public Optional<DispatchOutbox> findByOperation(
                TenantId tenantId, String operationType, String operationId) {
            return current().filter(outbox -> outbox.tenantId().equals(tenantId)
                    && outbox.operationType().equals(operationType)
                    && outbox.operationId().equals(operationId));
        }
        @Override public boolean compareAndSet(DispatchOutbox expected, DispatchOutbox next) {
            return value.compareAndSet(expected, next);
        }
    }

    private static final class MemorySessions implements BuildSessionRepository {
        private final AtomicReference<BuildSession> value;
        private MemorySessions(BuildSession initial) { value = new AtomicReference<>(initial); }
        private BuildSession current() { return value.get(); }
        @Override public void create(BuildSession session) { throw new UnsupportedOperationException(); }
        @Override public Optional<BuildSession> find(
                TenantId tenantId, BuildSessionId buildSessionId) {
            BuildSession current = value.get();
            return current.tenantId().equals(tenantId)
                            && current.buildSessionId().equals(buildSessionId)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public List<BuildSession> findCancelling(Instant updatedBefore, int limit) {
            BuildSession current = value.get();
            return current.status() == BuildSessionStatus.CANCELLING
                            && !current.updatedAt().isAfter(updatedBefore)
                            && limit > 0
                    ? List.of(current) : List.of();
        }
        @Override public boolean compareAndSet(BuildSession expected, BuildSession next) {
            return value.compareAndSet(expected, next);
        }
    }
}
