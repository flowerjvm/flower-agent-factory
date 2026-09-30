package io.github.flowerjvm.factory.application.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class WorkerCompletionServiceTest {
    @Test
    void duplicateCallbackTransitionsTerminalLedgerOnce() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        var requested = new WorkerRunRecord(
                new WorkerRunId("run-1"), new TenantId("tenant-a"), new BuildSessionId("session-1"),
                new WorkOrderId("work-1"), 1, "fake", "1", new WorkerCapabilities(java.util.Set.of()),
                WorkerRunStatus.REQUESTED, Optional.empty(), "operation-1", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), now.plusSeconds(300), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), 0, now, now);
        String attemptToken = "pr3-test-attempt-token";
        var dispatching = requested.startDispatch(
                new DispatchOutboxId("outbox-1"), "action-run-1",
                WorkerDispatchOperationIds.hashAttemptToken(attemptToken), now.plusSeconds(1));
        var waiting = dispatching.awaitExternal("fake-session-1", now.plusSeconds(2));
        var repository = new InMemoryWorkerRuns(waiting);
        var runtime = new RecordingCompletableRuntime();
        var service = service(repository, runtime, now.plusSeconds(2));
        var completion = new WorkerCompletion(
                dispatching.tenantId(), dispatching.workerRunId(), dispatching.operationId(), attemptToken,
                WorkerRunStatus.SUCCEEDED, Optional.of(new ArtifactReference("artifact:result")),
                Optional.of(new ContentHash("b".repeat(64))), "WORKER_SUCCEEDED", "done",
                WorkerRetryDisposition.NEVER, now.plusSeconds(3));

        assertEquals(WorkerCompletionDisposition.APPLIED, service.complete(completion));
        assertEquals(WorkerCompletionDisposition.DUPLICATE, service.complete(completion));
        assertEquals(1, repository.casWins);
        assertEquals(WorkerRunStatus.SUCCEEDED, repository.current.status());
        assertEquals(2, runtime.completions);
        assertEquals("action-run-1", runtime.lastRunId);
        assertEquals(attemptToken, runtime.lastAttemptToken);
    }

    @Test
    void wrongAttemptTokenCannotChangeTheLedger() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        var requested = requested(now);
        var dispatching = requested.startDispatch(
                new DispatchOutboxId("outbox-2"), "action-run-2",
                WorkerDispatchOperationIds.hashAttemptToken("correct-token"), now.plusSeconds(1));
        var repository = new InMemoryWorkerRuns(dispatching);
        var completion = new WorkerCompletion(
                dispatching.tenantId(), dispatching.workerRunId(), dispatching.operationId(), "wrong-token",
                WorkerRunStatus.FAILED, Optional.empty(), Optional.empty(), "WORKER_FAILED", "failed",
                WorkerRetryDisposition.NEVER, now.plusSeconds(2));

        assertEquals(
                WorkerCompletionDisposition.STALE_OR_CONFLICT,
                service(repository, new RecordingCompletableRuntime(), now.plusSeconds(2)).complete(completion));
        assertEquals(0, repository.casWins);
        assertEquals(WorkerRunStatus.DISPATCHING, repository.current.status());
    }

    @Test
    void dispatchingCallbackCannotBypassPendingOutboxAcceptance() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        var dispatching = requested(now).startDispatch(
                new DispatchOutboxId("outbox-early"), "action-run-early",
                WorkerDispatchOperationIds.hashAttemptToken("early-token"), now.plusSeconds(1));
        var repository = new InMemoryWorkerRuns(dispatching);
        var runtime = new RecordingCompletableRuntime();
        var completion = failure(dispatching, "early-token", "failed", now.plusSeconds(2));

        assertEquals(WorkerCompletionDisposition.STALE_OR_CONFLICT,
                service(repository, runtime, now.plusSeconds(2)).complete(completion));
        assertEquals(WorkerRunStatus.DISPATCHING, repository.current.status());
        assertEquals(0, repository.casWins);
        assertEquals(0, runtime.completions);
    }

    @Test
    void actionCompletionFailureLeavesWorkerRunWaitingForRetry() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        var dispatching = requested(now).startDispatch(
                new DispatchOutboxId("outbox-runtime-failure"), "action-run-runtime-failure",
                WorkerDispatchOperationIds.hashAttemptToken("runtime-failure-token"), now.plusSeconds(1));
        var waiting = dispatching.awaitExternal("fake-session-runtime-failure", now.plusSeconds(2));
        var repository = new InMemoryWorkerRuns(waiting);
        var runtime = new RecordingCompletableRuntime();
        runtime.failure = new IllegalStateException("simulated ActionRun store failure");

        assertThrows(
                IllegalStateException.class,
                () -> service(repository, runtime, now.plusSeconds(2)).complete(
                        failure(waiting, "runtime-failure-token", "failed", now.plusSeconds(3))));
        assertEquals(WorkerRunStatus.WAITING_EXTERNAL, repository.current.status());
        assertEquals(0, repository.casWins);
        assertEquals(1, runtime.completions);
    }

    @Test
    void nonMonotonicCompletionIsRejectedBeforeActionTerminalMutation() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        var waiting = requested(now).startDispatch(
                        new DispatchOutboxId("outbox-non-monotonic"),
                        "action-run-non-monotonic",
                        WorkerDispatchOperationIds.hashAttemptToken("non-monotonic-token"),
                        now.plusSeconds(1))
                .awaitExternal("fake-session-non-monotonic", now.plusSeconds(2));
        var repository = new InMemoryWorkerRuns(waiting);
        var runtime = new RecordingCompletableRuntime();

        assertThrows(
                IllegalStateException.class,
                () -> service(repository, runtime, now.plusSeconds(2)).complete(
                        failure(waiting, "non-monotonic-token", "invalid", now.plusSeconds(1))));
        assertEquals(0, runtime.completions);
        assertEquals(WorkerRunStatus.WAITING_EXTERNAL, repository.current.status());
    }

    @Test
    void failedDuplicateWithDifferentMessageIsAConflict() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        var dispatching = requested(now).startDispatch(
                new DispatchOutboxId("outbox-message"), "action-run-message",
                WorkerDispatchOperationIds.hashAttemptToken("message-token"), now.plusSeconds(1));
        var waiting = dispatching.awaitExternal("fake-session-message", now.plusSeconds(2));
        var repository = new InMemoryWorkerRuns(waiting);
        var runtime = new RecordingCompletableRuntime();
        var service = service(repository, runtime, now.plusSeconds(2));
        var first = failure(waiting, "message-token", "first message", now.plusSeconds(3));
        var changed = failure(waiting, "message-token", "changed message", now.plusSeconds(3));

        assertEquals(WorkerCompletionDisposition.APPLIED, service.complete(first));
        assertEquals(WorkerCompletionDisposition.STALE_OR_CONFLICT, service.complete(changed));
        assertEquals(1, runtime.completions);
        assertEquals(1, repository.casWins);
    }

    @Test
    void terminalCallbackWithChangedCompletionTimeIsAConflict() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        String token = "changed-time-token";
        var waiting = requested(now).startDispatch(
                        new DispatchOutboxId("outbox-changed-time"),
                        "action-run-changed-time",
                        WorkerDispatchOperationIds.hashAttemptToken(token),
                        now.plusSeconds(1))
                .awaitExternal("fake-session-changed-time", now.plusSeconds(2));
        var first = failure(waiting, token, "same result", now.plusSeconds(3));
        var terminal = waiting.complete(
                first.terminalStatus(),
                first.resultArtifactManifestRef(),
                first.resultHash(),
                first.code(),
                first.message(),
                first.retryDisposition(),
                first.completedAt());
        var repository = new InMemoryWorkerRuns(terminal);
        var runtime = new RecordingCompletableRuntime();
        var changedTime = failure(waiting, token, "same result", now.plusSeconds(4));

        assertEquals(
                WorkerCompletionDisposition.STALE_OR_CONFLICT,
                service(repository, runtime, now.plusSeconds(4)).complete(changedTime));
        assertEquals(0, runtime.completions);
        assertEquals(0, repository.casWins);
    }

    @Test
    void terminalDuplicateUsesDurableMicrosecondTimestampPrecision() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        String token = "microsecond-token";
        var waiting = requested(now).startDispatch(
                        new DispatchOutboxId("outbox-microsecond"),
                        "action-run-microsecond",
                        WorkerDispatchOperationIds.hashAttemptToken(token),
                        now.plusSeconds(1))
                .awaitExternal("fake-session-microsecond", now.plusSeconds(2));
        Instant callbackTime = now.plusSeconds(3).plusNanos(789);
        var completion = failure(waiting, token, "same durable timestamp", callbackTime);
        Instant durableTime = WorkerCompletionActionProjection.canonicalTimestamp(callbackTime);
        var terminal = waiting.complete(
                completion.terminalStatus(),
                completion.resultArtifactManifestRef(),
                completion.resultHash(),
                completion.code(),
                completion.message(),
                completion.retryDisposition(),
                durableTime);
        var repository = new InMemoryWorkerRuns(terminal);
        var runtime = new RecordingCompletableRuntime();

        assertEquals(
                WorkerCompletionDisposition.DUPLICATE,
                service(repository, runtime, now.plusSeconds(4)).complete(completion));
        assertEquals(1, runtime.completions);
        assertEquals(0, repository.casWins);
    }

    @Test
    void completionAtExactDeadlineDoesNotWinTheTerminalRace() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        var dispatching = requested(now).startDispatch(
                new DispatchOutboxId("outbox-deadline"), "action-run-deadline",
                WorkerDispatchOperationIds.hashAttemptToken("deadline-token"), now.plusSeconds(1));
        var waiting = dispatching.awaitExternal("fake-session-deadline", now.plusSeconds(2));
        var repository = new InMemoryWorkerRuns(waiting);
        var runtime = new RecordingCompletableRuntime();

        assertEquals(
                WorkerCompletionDisposition.STALE_OR_CONFLICT,
                service(repository, runtime, waiting.deadlineAt()).complete(
                        failure(waiting, "deadline-token", "late", waiting.deadlineAt())));
        assertEquals(WorkerRunStatus.WAITING_EXTERNAL, repository.current.status());
        assertEquals(0, runtime.completions);
        assertEquals(0, repository.casWins);
    }

    @Test
    void trustedDurableReceiptBeforeDeadlineCanCompleteAfterRestartPastDeadline() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        String token = "durable-receipt-token";
        var waiting = requested(now).startDispatch(
                        new DispatchOutboxId("outbox-durable-receipt"),
                        "action-run-durable-receipt",
                        WorkerDispatchOperationIds.hashAttemptToken(token),
                        now.plusSeconds(1))
                .awaitExternal("fake-session-durable-receipt", now.plusSeconds(2));
        var repository = new InMemoryWorkerRuns(waiting);
        var runtime = new RecordingCompletableRuntime();
        var service = service(repository, runtime, waiting.deadlineAt().plusSeconds(30));
        var completion = failure(
                waiting, token, "received before restart", waiting.deadlineAt().minusMillis(1));

        assertEquals(
                WorkerCompletionDisposition.STALE_OR_CONFLICT,
                service.complete(completion),
                "an ordinary caller cannot assert a historical trusted receipt");
        assertEquals(
                WorkerCompletionDisposition.APPLIED,
                service.completeFromTrustedInboxReceipt(completion));
        assertEquals(WorkerRunStatus.FAILED, repository.current.status());
        assertEquals(1, runtime.completions);
        assertEquals(1, repository.casWins);
    }

    @Test
    void trustedEarlyCallbackReceiptMayPredateTheLaterAcceptanceCommit() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        String token = "early-before-acceptance-token";
        var dispatching = requested(now).startDispatch(
                new DispatchOutboxId("outbox-early-before-acceptance"),
                "action-run-early-before-acceptance",
                WorkerDispatchOperationIds.hashAttemptToken(token),
                now.plusSeconds(1));
        Instant receivedAt = now.plusMillis(1_500);
        Instant acceptanceRecordedAt = now.plusSeconds(2);
        var waiting = dispatching.awaitExternal("fast-worker-session", acceptanceRecordedAt);
        var repository = new InMemoryWorkerRuns(waiting);
        var runtime = new RecordingCompletableRuntime();
        var service = service(repository, runtime, now.plusMillis(2_500));
        var completion = failure(waiting, token, "fast callback", receivedAt);

        assertThrows(
                IllegalStateException.class,
                () -> service.complete(completion),
                "an ordinary caller cannot assert the historical callback receipt");
        assertEquals(
                WorkerCompletionDisposition.APPLIED,
                service.completeFromTrustedInboxReceipt(completion));
        assertEquals(WorkerRunStatus.FAILED, repository.current.status());
        assertEquals(Optional.of(receivedAt), repository.current.completedAt());
        assertEquals(acceptanceRecordedAt, repository.current.updatedAt());
        assertEquals(1, runtime.completions);
    }

    @Test
    void canonicalCompletionWinnerProjectsAcrossConcurrentCancelHookCommit() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        String token = "completion-cancel-race-token";
        var waiting = requested(now).startDispatch(
                        new DispatchOutboxId("outbox-completion-cancel-race"),
                        "action-run-completion-cancel-race",
                        WorkerDispatchOperationIds.hashAttemptToken(token),
                        now.plusSeconds(1))
                .awaitExternal("session-completion-cancel-race", now.plusSeconds(2));
        Instant receiptAt = now.plusSeconds(3);
        Instant cancelCommittedAt = now.plusSeconds(4);
        var repository = new CancelDuringFirstCompletionCas(waiting, cancelCommittedAt);
        var runtime = new RecordingCompletableRuntime();
        var service = service(repository, runtime, now.plusSeconds(5));
        var completion = failure(waiting, token, "completion won Action CAS", receiptAt);

        assertEquals(
                WorkerCompletionDisposition.APPLIED,
                service.completeFromTrustedInboxReceipt(completion));
        assertEquals(WorkerRunStatus.FAILED, repository.current.status());
        assertEquals(
                Optional.of(WorkerCompletionActionProjection.canonicalTimestamp(receiptAt)),
                repository.current.completedAt());
        assertEquals(cancelCommittedAt, repository.current.updatedAt());
        assertEquals(2, repository.casAttempts);
        assertEquals(1, runtime.completions);
    }

    @Test
    void canonicalCompletionWinnerRetriesAcrossConcurrentStatusHeartbeat() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        String token = "completion-heartbeat-race-token";
        var waiting = requested(now).startDispatch(
                        new DispatchOutboxId("outbox-completion-heartbeat-race"),
                        "action-run-completion-heartbeat-race",
                        WorkerDispatchOperationIds.hashAttemptToken(token),
                        now.plusSeconds(1))
                .awaitExternal("session-completion-heartbeat-race", now.plusSeconds(2));
        Instant receiptAt = now.plusSeconds(3);
        Instant heartbeatAt = now.plusSeconds(4);
        var repository = new HeartbeatDuringFirstCompletionCas(waiting, heartbeatAt);
        var runtime = new RecordingCompletableRuntime();
        var service = service(repository, runtime, now.plusSeconds(5));
        var completion = failure(waiting, token, "completion won Action CAS", receiptAt);

        assertEquals(
                WorkerCompletionDisposition.APPLIED,
                service.completeFromTrustedInboxReceipt(completion));
        assertEquals(WorkerRunStatus.FAILED, repository.current.status());
        assertEquals(
                Optional.of(WorkerCompletionActionProjection.canonicalTimestamp(receiptAt)),
                repository.current.completedAt());
        assertEquals(heartbeatAt, repository.current.updatedAt());
        assertEquals(2, repository.casAttempts);
        assertEquals(1, runtime.completions);
    }

    @Test
    void terminalDuplicateRemainsCanonicalWhenRedeliveredAfterDeadline() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        String token = "late-duplicate-token";
        var waiting = requested(now).startDispatch(
                        new DispatchOutboxId("outbox-late-duplicate"),
                        "action-run-late-duplicate",
                        WorkerDispatchOperationIds.hashAttemptToken(token),
                        now.plusSeconds(1))
                .awaitExternal("fake-session-late-duplicate", now.plusSeconds(2));
        var completion = failure(waiting, token, "finished before deadline", now.plusSeconds(3));
        var terminal = waiting.complete(
                completion.terminalStatus(),
                completion.resultArtifactManifestRef(),
                completion.resultHash(),
                completion.code(),
                completion.message(),
                completion.retryDisposition(),
                completion.completedAt());
        var repository = new InMemoryWorkerRuns(terminal);
        var runtime = new RecordingCompletableRuntime();

        assertEquals(
                WorkerCompletionDisposition.DUPLICATE,
                service(repository, runtime, waiting.deadlineAt().plusSeconds(1)).complete(completion));
        assertEquals(1, runtime.completions);
        assertEquals(0, repository.casWins);
    }

    private static final class RecordingCompletableRuntime implements CompletableActionRuntime {
        private int completions;
        private String lastRunId;
        private String lastAttemptToken;
        private RuntimeException failure;
        private ActionExecutionResult canonicalResult;

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
            completions++;
            lastRunId = runId;
            lastAttemptToken = attemptToken;
            if (failure != null) {
                throw failure;
            }
            return canonicalResult == null ? result : canonicalResult;
        }

        @Override
        public ActionExecutionResult cancel(String runId, String reason) {
            throw new UnsupportedOperationException();
        }
    }

    private static WorkerRunRecord requested(Instant now) {
        return new WorkerRunRecord(
                new WorkerRunId("run-2"), new TenantId("tenant-a"), new BuildSessionId("session-1"),
                new WorkOrderId("work-1"), 1, "fake", "1", new WorkerCapabilities(java.util.Set.of()),
                WorkerRunStatus.REQUESTED, Optional.empty(), "operation-2", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), now.plusSeconds(300), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), 0, now, now);
    }

    private static WorkerCompletionService service(
            WorkerRunRepository repository,
            CompletableActionRuntime runtime,
            Instant now) {
        return new WorkerCompletionService(repository, runtime, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static WorkerCompletion failure(
            WorkerRunRecord workerRun,
            String attemptToken,
            String message,
            Instant completedAt) {
        return new WorkerCompletion(
                workerRun.tenantId(), workerRun.workerRunId(), workerRun.operationId(), attemptToken,
                WorkerRunStatus.FAILED, Optional.empty(), Optional.empty(), "WORKER_FAILED", message,
                WorkerRetryDisposition.NEVER, completedAt);
    }

    private static final class InMemoryWorkerRuns implements WorkerRunRepository {
        private final ConcurrentHashMap<WorkerRunId, WorkerRunRecord> values = new ConcurrentHashMap<>();
        private volatile WorkerRunRecord current;
        private volatile int casWins;

        private InMemoryWorkerRuns(WorkerRunRecord initial) {
            current = initial;
            values.put(initial.workerRunId(), initial);
        }

        @Override public void create(WorkerRunRecord workerRun) { values.putIfAbsent(workerRun.workerRunId(), workerRun); }

        @Override public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId) {
            WorkerRunRecord value = values.get(workerRunId);
            return value != null && value.tenantId().equals(tenantId) ? Optional.of(value) : Optional.empty();
        }

        @Override public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
            boolean won = values.replace(expected.workerRunId(), expected, next);
            if (won) { current = next; casWins++; }
            return won;
        }
    }

    private static final class CancelDuringFirstCompletionCas implements WorkerRunRepository {
        private final ConcurrentHashMap<WorkerRunId, WorkerRunRecord> values = new ConcurrentHashMap<>();
        private final Instant cancelCommittedAt;
        private volatile WorkerRunRecord current;
        private volatile int casAttempts;

        private CancelDuringFirstCompletionCas(WorkerRunRecord initial, Instant cancelCommittedAt) {
            this.current = initial;
            this.cancelCommittedAt = cancelCommittedAt;
            values.put(initial.workerRunId(), initial);
        }

        @Override public void create(WorkerRunRecord workerRun) { throw new UnsupportedOperationException(); }

        @Override public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId) {
            WorkerRunRecord value = values.get(workerRunId);
            return value != null && value.tenantId().equals(tenantId)
                    ? Optional.of(value) : Optional.empty();
        }

        @Override public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
            casAttempts++;
            if (casAttempts == 1) {
                WorkerRunRecord cancelRequested = expected.requestCancellation(cancelCommittedAt);
                values.replace(expected.workerRunId(), expected, cancelRequested);
                current = cancelRequested;
                return false;
            }
            boolean won = values.replace(expected.workerRunId(), expected, next);
            if (won) current = next;
            return won;
        }
    }

    private static final class HeartbeatDuringFirstCompletionCas implements WorkerRunRepository {
        private final ConcurrentHashMap<WorkerRunId, WorkerRunRecord> values = new ConcurrentHashMap<>();
        private final Instant heartbeatAt;
        private volatile WorkerRunRecord current;
        private volatile int casAttempts;

        private HeartbeatDuringFirstCompletionCas(WorkerRunRecord initial, Instant heartbeatAt) {
            this.current = initial;
            this.heartbeatAt = heartbeatAt;
            values.put(initial.workerRunId(), initial);
        }

        @Override public void create(WorkerRunRecord workerRun) { throw new UnsupportedOperationException(); }

        @Override public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId) {
            WorkerRunRecord value = values.get(workerRunId);
            return value != null && value.tenantId().equals(tenantId)
                    ? Optional.of(value) : Optional.empty();
        }

        @Override public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
            casAttempts++;
            if (casAttempts == 1) {
                WorkerRunRecord heartbeat = expected.observeHeartbeat(heartbeatAt);
                values.replace(expected.workerRunId(), expected, heartbeat);
                current = heartbeat;
                return false;
            }
            boolean won = values.replace(expected.workerRunId(), expected, next);
            if (won) current = next;
            return won;
        }
    }
}
