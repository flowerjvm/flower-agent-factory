package io.github.flowerjvm.factory.application.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapability;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkerActionRunRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    private static final TenantId TENANT = new TenantId("tenant-recovery");

    @Test
    void terminalActionRepairsWaitingWorkerRunAfterCrashBeforeDomainCas() {
        WorkOrder order = workOrder();
        String token = "terminal-token";
        WorkerRunRecord waiting = requested(order).startDispatch(
                        new DispatchOutboxId("outbox-recovery"),
                        "action-recovery",
                        WorkerDispatchOperationIds.hashAttemptToken(token),
                        NOW.plusSeconds(1))
                .awaitExternal("fake-session", NOW.plusSeconds(2));
        WorkerCompletion completion = new WorkerCompletion(
                TENANT,
                waiting.workerRunId(),
                waiting.operationId(),
                token,
                WorkerRunStatus.SUCCEEDED,
                Optional.of(new ArtifactReference("artifact:result")),
                Optional.of(hash("9")),
                "WORKER_SUCCEEDED",
                "completed",
                WorkerRetryDisposition.NEVER,
                NOW.plusSeconds(3));
        ActionRun owner = ownerRun(order, requested(order), token).toBuilder()
                .status(ActionRunStatus.SUCCEEDED)
                .attemptToken(token)
                .externalOperationId(waiting.operationId())
                .result(WorkerCompletionActionProjection.toActionResult(completion))
                .updatedAt(NOW.plusSeconds(3))
                .build();
        InMemoryWorkers workers = new InMemoryWorkers(waiting);
        WorkerActionRunRecovery recovery = new WorkerActionRunRecovery(workers, new OneRunStore(owner));

        WorkerRunRecord reconciled = recovery.reconcileTerminalCompletion(order, waiting);

        assertEquals(WorkerRunStatus.SUCCEEDED, reconciled.status());
        assertEquals(Optional.of(hash("9")), reconciled.resultHash());
        assertEquals(1, workers.casWins);
        assertTrue(recovery.terminalOwnerMatches(order, reconciled));
    }

    @Test
    void resumableOwnerBeforeDomainClaimStopsASecondDispatchAttempt() {
        WorkOrder order = workOrder();
        WorkerRunRecord requested = requested(order);
        ActionRun owner = ownerRun(order, requested, "unclaimed-token").toBuilder()
                .status(ActionRunStatus.RUNNING)
                .attemptToken("unclaimed-token")
                .build();
        WorkerActionRunRecovery recovery = new WorkerActionRunRecovery(
                new InMemoryWorkers(requested), new OneRunStore(owner));

        assertTrue(recovery.hasUnclaimedResumableOwner(order, requested));
        assertFalse(recovery.terminalOwnerMatches(order, requested));
    }

    @Test
    void terminalDuplicateOwnerBeforeDomainClaimIsClassifiedWithoutRedispatch() {
        WorkOrder order = workOrder();
        WorkerRunRecord requested = requested(order);
        ActionRun owner = ownerRun(order, requested, "orphan-token").toBuilder()
                .status(ActionRunStatus.FAILED)
                .attemptToken("orphan-token")
                .externalOperationId("")
                .result(ActionExecutionResult.failed(
                        "ACTION_EXECUTION_EXCEPTION", "dispatch transaction rolled back"))
                .updatedAt(NOW.plusSeconds(1))
                .build();
        WorkerActionRunRecovery recovery = new WorkerActionRunRecovery(
                new InMemoryWorkers(requested),
                new OneRunStore(owner),
                null,
                (tenantId, actionId, key) -> Optional.of(owner.runId()));

        var orphan = recovery.findUnclaimedOwner(order, requested).orElseThrow();

        assertEquals(
                WorkerActionRunRecovery.UnclaimedOwnerDisposition.TERMINAL_NO_EFFECT,
                orphan.disposition());
        assertEquals("WORKER_ACTION_ORPHAN_NO_EFFECT", orphan.code());
        assertFalse(recovery.hasUnclaimedResumableOwner(order, requested));
    }

    @Test
    void terminalDuplicateOwnerWithOperationIdentityRequiresManualReconciliation() {
        WorkOrder order = workOrder();
        WorkerRunRecord requested = requested(order);
        ActionRun owner = ownerRun(order, requested, "uncertain-token").toBuilder()
                .status(ActionRunStatus.FAILED)
                .attemptToken("uncertain-token")
                .externalOperationId(requested.operationId())
                .result(ActionExecutionResult.failed(
                        "ACTION_RUNTIME_FAILED", "outcome after external operation is uncertain"))
                .updatedAt(NOW.plusSeconds(1))
                .build();
        WorkerActionRunRecovery recovery = new WorkerActionRunRecovery(
                new InMemoryWorkers(requested),
                new OneRunStore(owner),
                null,
                (tenantId, actionId, key) -> Optional.of(owner.runId()));

        var orphan = recovery.findUnclaimedOwner(order, requested).orElseThrow();

        assertEquals(
                WorkerActionRunRecovery.UnclaimedOwnerDisposition.TERMINAL_EFFECT_UNCERTAIN,
                orphan.disposition());
        assertEquals("WORKER_ACTION_ORPHAN_EFFECT_UNCERTAIN", orphan.code());
    }

    @Test
    void terminalTimeoutRepairsWaitingWorkerAtTheExactPersistedDeadline() {
        WorkOrder order = workOrder();
        String token = "timeout-token";
        WorkerRunRecord waiting = requested(order).startDispatch(
                        new DispatchOutboxId("outbox-timeout"),
                        "action-recovery",
                        WorkerDispatchOperationIds.hashAttemptToken(token),
                        NOW.plusSeconds(1))
                .awaitExternal("fake-timeout", NOW.plusSeconds(2));
        ActionRun owner = ownerRun(order, requested(order), token).toBuilder()
                .status(ActionRunStatus.FAILED)
                .attemptToken(token)
                .externalOperationId(waiting.operationId())
                .result(WorkerCompletionActionProjection.toTimeoutActionResult(waiting))
                .updatedAt(waiting.deadlineAt())
                .build();
        InMemoryWorkers workers = new InMemoryWorkers(waiting);
        WorkerActionRunRecovery recovery = new WorkerActionRunRecovery(workers, new OneRunStore(owner));

        WorkerRunRecord reconciled = recovery.reconcileTerminalCompletion(order, waiting);

        assertEquals(WorkerRunStatus.TIMED_OUT, reconciled.status());
        assertEquals(Optional.of(waiting.deadlineAt()), reconciled.completedAt());
        assertEquals(
                Optional.of(WorkerCompletionActionProjection.WORKER_RUN_TIMED_OUT),
                reconciled.code());
        assertTrue(recovery.terminalOwnerMatches(order, reconciled));
    }

    @Test
    void earlierEffectiveTimeoutRepairsWaitingWorkerAfterActionFirstCrash() {
        WorkOrder order = workOrder();
        Instant effectiveSessionCutoff = NOW.plusSeconds(100);
        String token = "early-timeout-token";
        WorkerRunRecord waiting = requested(order).startDispatch(
                        new DispatchOutboxId("outbox-early-timeout"),
                        "action-early-timeout",
                        WorkerDispatchOperationIds.hashAttemptToken(token),
                        NOW.plusSeconds(1))
                .awaitExternal("fake-early-timeout", NOW.plusSeconds(2));
        ActionRun owner = ownerRun(order, requested(order), token).toBuilder()
                .runId("action-early-timeout")
                .status(ActionRunStatus.FAILED)
                .attemptToken(token)
                .externalOperationId(waiting.operationId())
                .result(WorkerCompletionActionProjection.toTimeoutActionResult(
                        waiting, effectiveSessionCutoff))
                .updatedAt(effectiveSessionCutoff)
                .build();
        InMemoryWorkers workers = new InMemoryWorkers(waiting);
        WorkerActionRunRecovery recovery = new WorkerActionRunRecovery(workers, new OneRunStore(owner));

        WorkerRunRecord reconciled = recovery.reconcileTerminalCompletion(order, waiting);

        assertEquals(WorkerRunStatus.TIMED_OUT, reconciled.status());
        assertEquals(Optional.of(effectiveSessionCutoff), reconciled.completedAt());
        assertTrue(recovery.terminalOwnerMatches(order, reconciled));
    }

    @Test
    void earlierSessionCutoffWinsAfterCallbackPrecheckWithoutSplittingActionAndWorkerTruth() throws Exception {
        WorkOrder order = workOrder();
        Instant effectiveSessionCutoff = NOW.plusSeconds(100);
        String token = "race-token";
        WorkerRunRecord waiting = requested(order).startDispatch(
                        new DispatchOutboxId("outbox-race"),
                        "action-recovery",
                        WorkerDispatchOperationIds.hashAttemptToken(token),
                        NOW.plusSeconds(1))
                .awaitExternal("fake-race", NOW.plusSeconds(2));
        ActionRun owner = ownerRun(order, requested(order), token).toBuilder()
                .status(ActionRunStatus.WAITING_EXTERNAL)
                .attemptToken(token)
                .externalOperationId(waiting.operationId())
                .dueAt(waiting.deadlineAt())
                .updatedAt(NOW.plusSeconds(2))
                .build();
        InMemoryWorkers workers = new InMemoryWorkers(waiting);
        PausingFirstTerminalRuntime runtime = new PausingFirstTerminalRuntime();
        WorkerCompletionService callbacks = new WorkerCompletionService(
                workers,
                runtime,
                java.time.Clock.fixed(effectiveSessionCutoff.minusMillis(1), java.time.ZoneOffset.UTC));
        WorkerActionRunRecovery recovery = new WorkerActionRunRecovery(
                workers,
                new OneRunStore(owner),
                runtime,
                WorkerActionDuplicateOwnerLookup.none());
        WorkerCompletion callback = new WorkerCompletion(
                TENANT,
                waiting.workerRunId(),
                waiting.operationId(),
                token,
                WorkerRunStatus.SUCCEEDED,
                Optional.of(new ArtifactReference("artifact:race-result")),
                Optional.of(hash("8")),
                "WORKER_SUCCEEDED",
                "callback arrived before the deadline",
                WorkerRetryDisposition.NEVER,
                effectiveSessionCutoff.minusMillis(1));

        var executor = Executors.newSingleThreadExecutor();
        try {
            var callbackResult = executor.submit(() -> callbacks.complete(callback));
            assertTrue(runtime.callbackEntered.await(5, TimeUnit.SECONDS));

            WorkerRunRecord deadlineWinner = recovery.reconcileDeadline(
                    order, waiting, effectiveSessionCutoff, effectiveSessionCutoff);
            runtime.releaseCallback.countDown();

            assertEquals(WorkerRunStatus.TIMED_OUT, deadlineWinner.status());
            assertEquals(Optional.of(effectiveSessionCutoff), deadlineWinner.completedAt());
            assertEquals(WorkerCompletionDisposition.STALE_OR_CONFLICT, callbackResult.get(5, TimeUnit.SECONDS));
            assertEquals(WorkerRunStatus.TIMED_OUT, workers.value.status());
            assertEquals(
                    WorkerCompletionActionProjection.toTimeoutActionResult(waiting, effectiveSessionCutoff),
                    runtime.winner.get());
            assertEquals(1, workers.casWins);
        } finally {
            runtime.releaseCallback.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void wrongTenantOrOperationCannotRepairTheWorkerLedger() {
        WorkOrder order = workOrder();
        String token = "scope-token";
        WorkerRunRecord waiting = requested(order).startDispatch(
                        new DispatchOutboxId("outbox-scope"),
                        "action-recovery",
                        WorkerDispatchOperationIds.hashAttemptToken(token),
                        NOW.plusSeconds(1))
                .awaitExternal("fake-scope", NOW.plusSeconds(2));
        WorkerCompletion completion = new WorkerCompletion(
                TENANT, waiting.workerRunId(), waiting.operationId(), token, WorkerRunStatus.FAILED,
                Optional.empty(), Optional.empty(), "WORKER_FAILED", "failed",
                WorkerRetryDisposition.NEVER, NOW.plusSeconds(3));
        ActionRun wrongOwner = ownerRun(order, requested(order), token).toBuilder()
                .tenantId("other-tenant")
                .status(ActionRunStatus.FAILED)
                .attemptToken(token)
                .externalOperationId("other-operation")
                .result(WorkerCompletionActionProjection.toActionResult(completion))
                .updatedAt(NOW.plusSeconds(3))
                .build();
        InMemoryWorkers workers = new InMemoryWorkers(waiting);
        WorkerActionRunRecovery recovery = new WorkerActionRunRecovery(workers, new OneRunStore(wrongOwner));

        assertThrows(
                IllegalStateException.class,
                () -> recovery.reconcileTerminalCompletion(order, waiting));
        assertEquals(WorkerRunStatus.WAITING_EXTERNAL, workers.value.status());
        assertEquals(0, workers.casWins);
    }

    private static ActionRun ownerRun(WorkOrder order, WorkerRunRecord requested, String token) {
        ActionProposal proposal = ActionProposal.builder(WorkerDispatchAction.ACTION_ID)
                .proposalId("proposal-recovery")
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("factory-builder")
                .reason("test recovery")
                .input(Map.of(
                        WorkerDispatchAction.WORK_ORDER_ID, order.workOrderId().value(),
                        WorkerDispatchAction.WORKER_RUN_ID, requested.workerRunId().value(),
                        WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION, requested.version()))
                .idempotencyKey(WorkerDispatchIdempotencyKeys.derive(order, requested))
                .build();
        return ActionRun.requested(
                        proposal,
                        new ExecutionContext(TENANT.value(), "principal", "action-recovery", "trace", Map.of()))
                .toBuilder()
                .status(ActionRunStatus.RUNNING)
                .attemptToken(token)
                .updatedAt(NOW.plusSeconds(1))
                .build();
    }

    private static WorkOrder workOrder() {
        return new WorkOrder(
                new WorkOrderId("order-recovery"), TENANT, new BuildSessionId("session-recovery"),
                "DESIGN_AGENT", "design", 1, Optional.empty(), Optional.empty(), Optional.empty(),
                new ArtifactReference("artifact:instruction"), hash("1"),
                new ArtifactReference("artifact:input"), hash("2"), "workspace:recovery",
                List.of("inputs"), List.of("candidate"), Set.of(new WorkerCapability("java")),
                "agent-blueprint", "1", new ArtifactReference("artifact:policy"),
                NOW.plusSeconds(300), 1, "logical-recovery", WorkOrderCreatorType.SYSTEM,
                "factory-builder", NOW);
    }

    private static WorkerRunRecord requested(WorkOrder order) {
        WorkerRunId id = new WorkerRunId("worker-recovery");
        return new WorkerRunRecord(
                id, TENANT, order.buildSessionId(), order.workOrderId(), 1, "fake", "1",
                new WorkerCapabilities(Set.of(new WorkerCapability("java"))), WorkerRunStatus.REQUESTED,
                Optional.empty(), WorkerDispatchOperationIds.derive(
                        order.tenantId(), order.workOrderId(), id, 1, "fake"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), NOW.plusSeconds(300), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), 0, NOW, NOW);
    }

    private static ContentHash hash(String value) {
        return new ContentHash(value.repeat(64));
    }

    private static final class InMemoryWorkers implements WorkerRunRepository {
        private volatile WorkerRunRecord value;
        private volatile int casWins;

        private InMemoryWorkers(WorkerRunRecord value) {
            this.value = value;
        }

        @Override public void create(WorkerRunRecord workerRun) { throw new UnsupportedOperationException(); }
        @Override public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId) {
            return value.tenantId().equals(tenantId) && value.workerRunId().equals(workerRunId)
                    ? Optional.of(value) : Optional.empty();
        }
        @Override public synchronized boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
            if (!value.equals(expected)) return false;
            value = next;
            casWins++;
            return true;
        }
    }

    private static final class PausingFirstTerminalRuntime implements CompletableActionRuntime {
        private final AtomicReference<ActionExecutionResult> winner = new AtomicReference<>();
        private final CountDownLatch callbackEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCallback = new CountDownLatch(1);

        @Override
        public ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionExecutionResult resume(String runId, ApprovalDecision decision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionExecutionResult complete(
                String runId,
                String attemptToken,
                ActionExecutionResult result) {
            if ("WORKER_SUCCEEDED".equals(result.code())) {
                callbackEntered.countDown();
                try {
                    if (!releaseCallback.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test callback pause timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test callback interrupted", exception);
                }
            }
            winner.compareAndSet(null, result);
            return winner.get();
        }

        @Override
        public ActionExecutionResult cancel(String runId, String reason) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class OneRunStore implements RunStore {
        private final ConcurrentHashMap<String, ActionRun> runs = new ConcurrentHashMap<>();
        private OneRunStore(ActionRun run) { runs.put(run.runId(), run); }
        @Override public ActionRun create(ActionRun run) { runs.put(run.runId(), run); return run; }
        @Override public Optional<ActionRun> find(String runId) { return Optional.ofNullable(runs.get(runId)); }
        @Override public boolean compareAndSet(ActionRun expected, ActionRun next) {
            return runs.replace(expected.runId(), expected, next);
        }
        @Override public List<ActionRun> findResumable(String tenantId) {
            return runs.values().stream()
                    .filter(run -> run.tenantId().equals(tenantId) && !run.status().isTerminal())
                    .toList();
        }
    }
}
