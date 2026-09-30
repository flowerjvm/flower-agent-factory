package io.github.flowerjvm.factory.application.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.candidate.CandidateIngestionService;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerProtocol;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkerCallbackProcessorBoundaryTest {
    private static final Instant START = Instant.parse("2026-08-20T00:00:00Z");
    private static final TenantId TENANT = new TenantId("tenant-callback");
    private static final BuildSessionId SESSION = new BuildSessionId("session-callback");
    private static final WorkOrderId ORDER_ID = new WorkOrderId("order-callback");
    private static final WorkerRunId RUN_ID = new WorkerRunId("run-callback");
    private static final String OPERATION = "operation-callback";
    private static final String ACTION_RUN = "action-run-callback";
    private static final String ATTEMPT_TOKEN = "callback-attempt-token";

    @Test
    void earlyCallbackAtExactProcessingDeadlineTerminatesAsAcceptanceOrphan() {
        Instant deadline = START.plusSeconds(30);
        Instant receivedAt = deadline.minusMillis(1);
        MutableClock clock = new MutableClock(deadline);
        WorkOrder order = workOrder(deadline);
        WorkerRunRecord run = requested(deadline)
                .startDispatch(
                        new DispatchOutboxId("dispatch-callback"),
                        ACTION_RUN,
                        WorkerDispatchOperationIds.hashAttemptToken(ATTEMPT_TOKEN),
                        START.plusSeconds(1));
        CodingWorkerCompletionPayload payload = new CodingWorkerCompletionPayload(
                WorkerProtocol.COMPLETION_SCHEMA_VERSION,
                "event-callback",
                ORDER_ID,
                RUN_ID,
                OPERATION,
                WorkerAttemptProofs.create(
                        ATTEMPT_TOKEN, "event-callback", OPERATION, RUN_ID),
                WorkerRunStatus.FAILED,
                Optional.empty(),
                "PROVIDER_FAILURE");
        byte[] body = "callback-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ContentHash bodyHash = hash(body);
        ArtifactReference bodyRef = new ArtifactReference("artifact:callback-body");
        Artifact bodyArtifact = new Artifact(TENANT, bodyRef, bodyHash, "application/json", body);
        WorkerCallbackInboxEntry received = WorkerCallbackInboxEntry.received(
                "callback-id",
                TENANT,
                "codex-worker",
                payload.eventId(),
                ORDER_ID,
                RUN_ID,
                OPERATION,
                WorkerDispatchOperationIds.hashAttemptToken(ATTEMPT_TOKEN),
                bodyRef,
                bodyHash,
                receivedAt);
        InMemoryInbox inbox = new InMemoryInbox(received);
        WorkerProtocolArtifacts protocolArtifacts = new WorkerProtocolArtifacts(
                artifactStore(bodyArtifact), decoder(payload));
        InMemoryRunStore runStore = new InMemoryRunStore();
        runStore.create(actionRun(deadline));
        InMemoryWorkerRuns workerRuns = new InMemoryWorkerRuns(run);
        CandidateIngestionService ingestion = new CandidateIngestionService(
                protocolArtifacts, emptyCandidates());
        WorkerCompletionService completions = new WorkerCompletionService(
                workerRuns, unusedRuntime(), clock);
        WorkerCallbackProcessor processor = new WorkerCallbackProcessor(
                inbox,
                protocolArtifacts,
                workOrders(order),
                workerRuns,
                runStore,
                ingestion,
                completions,
                new ConservativeWorkerCompletionClassifier(),
                clock,
                Duration.ofMinutes(1),
                Duration.ofSeconds(1));

        assertEquals(true, processor.tickOnce());
        assertEquals(WorkerCallbackInboxStatus.MANUAL_REVIEW, inbox.current().status());
        assertEquals(
                Optional.of(WorkerCallbackProcessor.ACCEPTANCE_ORPHANED),
                inbox.current().lastCode());
        assertEquals(WorkerRunStatus.DISPATCHING, workerRuns.current().status());
        assertEquals(ActionRunStatus.RUNNING,
                runStore.find(ACTION_RUN).orElseThrow().status());
    }

    @Test
    void acceptedWaitingExternalCallbackIsAppliedImmediately() {
        Instant deadline = START.plusSeconds(30);
        Instant receivedAt = START.plusSeconds(5);
        MutableClock clock = new MutableClock(receivedAt);
        WorkOrder order = workOrder(deadline);
        WorkerRunRecord waiting = requested(deadline)
                .startDispatch(
                        new DispatchOutboxId("dispatch-callback"),
                        ACTION_RUN,
                        WorkerDispatchOperationIds.hashAttemptToken(ATTEMPT_TOKEN),
                        START.plusSeconds(1))
                .awaitExternal(OPERATION, START.plusSeconds(2));
        CodingWorkerCompletionPayload payload = new CodingWorkerCompletionPayload(
                WorkerProtocol.COMPLETION_SCHEMA_VERSION,
                "event-callback",
                ORDER_ID,
                RUN_ID,
                OPERATION,
                WorkerAttemptProofs.create(
                        ATTEMPT_TOKEN, "event-callback", OPERATION, RUN_ID),
                WorkerRunStatus.FAILED,
                Optional.empty(),
                "PROVIDER_FAILURE");
        byte[] body = "callback-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ContentHash bodyHash = hash(body);
        ArtifactReference bodyRef = new ArtifactReference("artifact:callback-body");
        WorkerCallbackInboxEntry received = WorkerCallbackInboxEntry.received(
                "callback-id",
                TENANT,
                "codex-worker",
                payload.eventId(),
                ORDER_ID,
                RUN_ID,
                OPERATION,
                WorkerDispatchOperationIds.hashAttemptToken(ATTEMPT_TOKEN),
                bodyRef,
                bodyHash,
                receivedAt);
        InMemoryInbox inbox = new InMemoryInbox(received);
        WorkerProtocolArtifacts protocolArtifacts = new WorkerProtocolArtifacts(
                artifactStore(new Artifact(
                        TENANT, bodyRef, bodyHash, "application/json", body)),
                decoder(payload));
        InMemoryRunStore runStore = new InMemoryRunStore();
        runStore.create(actionRun(deadline, ActionRunStatus.WAITING_EXTERNAL));
        InMemoryWorkerRuns workerRuns = new InMemoryWorkerRuns(waiting);
        RecordingRuntime runtime = new RecordingRuntime();
        WorkerCallbackProcessor processor = new WorkerCallbackProcessor(
                inbox,
                protocolArtifacts,
                workOrders(order),
                workerRuns,
                runStore,
                new CandidateIngestionService(protocolArtifacts, emptyCandidates()),
                new WorkerCompletionService(workerRuns, runtime, clock),
                new ConservativeWorkerCompletionClassifier(),
                clock,
                Duration.ofMinutes(1),
                Duration.ofSeconds(1));

        assertEquals(true, processor.tickOnce());
        assertEquals(WorkerCallbackInboxStatus.APPLIED, inbox.current().status());
        assertEquals(WorkerRunStatus.FAILED, workerRuns.current().status());
        assertEquals(1, runtime.completions.get());
    }

    @Test
    void terminalActionWithTransientWorkerCasFailureKeepsInboxRetryable() {
        Instant deadline = START.plusSeconds(30);
        Instant receivedAt = START.plusSeconds(5);
        MutableClock clock = new MutableClock(receivedAt);
        WorkOrder order = workOrder(deadline);
        WorkerRunRecord waiting = requested(deadline)
                .startDispatch(
                        new DispatchOutboxId("dispatch-transient-cas"),
                        ACTION_RUN,
                        WorkerDispatchOperationIds.hashAttemptToken(ATTEMPT_TOKEN),
                        START.plusSeconds(1))
                .awaitExternal(OPERATION, START.plusSeconds(2));
        CodingWorkerCompletionPayload payload = new CodingWorkerCompletionPayload(
                WorkerProtocol.COMPLETION_SCHEMA_VERSION,
                "event-transient-cas",
                ORDER_ID,
                RUN_ID,
                OPERATION,
                WorkerAttemptProofs.create(
                        ATTEMPT_TOKEN, "event-transient-cas", OPERATION, RUN_ID),
                WorkerRunStatus.FAILED,
                Optional.empty(),
                "PROVIDER_FAILURE");
        byte[] body = "callback-transient-cas".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ContentHash bodyHash = hash(body);
        ArtifactReference bodyRef = new ArtifactReference("artifact:callback-transient-cas");
        WorkerCallbackInboxEntry received = WorkerCallbackInboxEntry.received(
                "callback-transient-cas", TENANT, "codex-worker", payload.eventId(),
                ORDER_ID, RUN_ID, OPERATION,
                WorkerDispatchOperationIds.hashAttemptToken(ATTEMPT_TOKEN),
                bodyRef, bodyHash, receivedAt);
        InMemoryInbox inbox = new InMemoryInbox(received);
        WorkerProtocolArtifacts protocolArtifacts = new WorkerProtocolArtifacts(
                artifactStore(new Artifact(
                        TENANT, bodyRef, bodyHash, "application/json", body)),
                decoder(payload));
        InMemoryRunStore runStore = new InMemoryRunStore();
        runStore.create(actionRun(deadline, ActionRunStatus.WAITING_EXTERNAL));
        InMemoryWorkerRuns delegate = new InMemoryWorkerRuns(waiting);
        AtomicInteger casAttempts = new AtomicInteger();
        WorkerRunRepository transientWorkerRuns = new WorkerRunRepository() {
            @Override public void create(WorkerRunRecord run) { delegate.create(run); }
            @Override public Optional<WorkerRunRecord> find(
                    TenantId tenantId, WorkerRunId workerRunId) {
                return delegate.find(tenantId, workerRunId);
            }
            @Override public boolean compareAndSet(
                    WorkerRunRecord expected, WorkerRunRecord next) {
                if (casAttempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("simulated transient WorkerRun DB failure");
                }
                return delegate.compareAndSet(expected, next);
            }
        };
        CompletableActionRuntime runtime = new CompletableActionRuntime() {
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
                ActionRun current = runStore.find(runId).orElseThrow();
                if (current.status().isTerminal()) {
                    return current.result();
                }
                ActionRun terminal = current.toBuilder()
                        .version(current.version() + 1)
                        .status(result.terminalSuccess()
                                ? ActionRunStatus.SUCCEEDED : ActionRunStatus.FAILED)
                        .result(result)
                        .updatedAt(clock.instant())
                        .build();
                if (!runStore.compareAndSet(current, terminal)) {
                    throw new IllegalStateException("simulated ActionRun CAS lost");
                }
                return result;
            }
            @Override public ActionExecutionResult cancel(String runId, String reason) {
                throw new UnsupportedOperationException();
            }
        };
        WorkerCallbackProcessor processor = new WorkerCallbackProcessor(
                inbox, protocolArtifacts, workOrders(order), transientWorkerRuns, runStore,
                new CandidateIngestionService(protocolArtifacts, emptyCandidates()),
                new WorkerCompletionService(transientWorkerRuns, runtime, clock),
                new ConservativeWorkerCompletionClassifier(), clock,
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThrows(IllegalStateException.class, processor::tickOnce);
        assertEquals(WorkerCallbackInboxStatus.PROCESSING, inbox.current().status());
        assertEquals(ActionRunStatus.FAILED, runStore.find(ACTION_RUN).orElseThrow().status());
        assertEquals(WorkerRunStatus.WAITING_EXTERNAL, delegate.current().status());

        clock.now = receivedAt.plusSeconds(2);
        assertEquals(true, processor.tickOnce());
        assertEquals(WorkerCallbackInboxStatus.APPLIED, inbox.current().status());
        assertEquals(WorkerRunStatus.FAILED, delegate.current().status());
        assertEquals(2, casAttempts.get());
    }

    @Test
    void fastCallbackReceivedBeforeAcceptanceCommitIsAppliedAfterTheRunParks() {
        Instant deadline = START.plusSeconds(30);
        Instant receivedAt = START.plusMillis(1_500);
        Instant acceptedAt = START.plusSeconds(2);
        MutableClock clock = new MutableClock(START.plusMillis(2_500));
        WorkOrder order = workOrder(deadline);
        WorkerRunRecord waiting = requested(deadline)
                .startDispatch(
                        new DispatchOutboxId("dispatch-fast-callback"),
                        ACTION_RUN,
                        WorkerDispatchOperationIds.hashAttemptToken(ATTEMPT_TOKEN),
                        START.plusSeconds(1))
                .awaitExternal(OPERATION, acceptedAt);
        CodingWorkerCompletionPayload payload = new CodingWorkerCompletionPayload(
                WorkerProtocol.COMPLETION_SCHEMA_VERSION,
                "event-fast-callback",
                ORDER_ID,
                RUN_ID,
                OPERATION,
                WorkerAttemptProofs.create(
                        ATTEMPT_TOKEN, "event-fast-callback", OPERATION, RUN_ID),
                WorkerRunStatus.FAILED,
                Optional.empty(),
                "PROVIDER_FAILURE");
        byte[] body = "fast-callback-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ContentHash bodyHash = hash(body);
        ArtifactReference bodyRef = new ArtifactReference("artifact:fast-callback-body");
        WorkerCallbackInboxEntry received = WorkerCallbackInboxEntry.received(
                "callback-fast",
                TENANT,
                "codex-worker",
                payload.eventId(),
                ORDER_ID,
                RUN_ID,
                OPERATION,
                WorkerDispatchOperationIds.hashAttemptToken(ATTEMPT_TOKEN),
                bodyRef,
                bodyHash,
                receivedAt);
        InMemoryInbox inbox = new InMemoryInbox(received);
        WorkerProtocolArtifacts protocolArtifacts = new WorkerProtocolArtifacts(
                artifactStore(new Artifact(
                        TENANT, bodyRef, bodyHash, "application/json", body)),
                decoder(payload));
        InMemoryRunStore runStore = new InMemoryRunStore();
        runStore.create(actionRun(deadline, ActionRunStatus.WAITING_EXTERNAL));
        InMemoryWorkerRuns workerRuns = new InMemoryWorkerRuns(waiting);
        RecordingRuntime runtime = new RecordingRuntime();
        WorkerCallbackProcessor processor = new WorkerCallbackProcessor(
                inbox,
                protocolArtifacts,
                workOrders(order),
                workerRuns,
                runStore,
                new CandidateIngestionService(protocolArtifacts, emptyCandidates()),
                new WorkerCompletionService(workerRuns, runtime, clock),
                new ConservativeWorkerCompletionClassifier(),
                clock,
                Duration.ofMinutes(1),
                Duration.ofSeconds(1));

        assertEquals(true, processor.tickOnce());
        assertEquals(WorkerCallbackInboxStatus.APPLIED, inbox.current().status());
        assertEquals(WorkerRunStatus.FAILED, workerRuns.current().status());
        assertEquals(Optional.of(receivedAt), workerRuns.current().completedAt());
        assertEquals(acceptedAt, workerRuns.current().updatedAt());
        assertEquals(1, runtime.completions.get());
    }

    @Test
    void canonicalActionCancellationSupersedesPreviouslyReceivedCompletion() {
        Instant deadline = START.plusSeconds(30);
        Instant receivedAt = START.plusSeconds(3);
        MutableClock clock = new MutableClock(START.plusSeconds(5));
        WorkOrder order = workOrder(deadline);
        WorkerRunRecord cancelRequested = requested(deadline)
                .startDispatch(
                        new DispatchOutboxId("dispatch-callback"),
                        ACTION_RUN,
                        WorkerDispatchOperationIds.hashAttemptToken(ATTEMPT_TOKEN),
                        START.plusSeconds(1))
                .awaitExternal(OPERATION, START.plusSeconds(2))
                .requestCancellation(START.plusSeconds(4));
        CodingWorkerCompletionPayload payload = new CodingWorkerCompletionPayload(
                WorkerProtocol.COMPLETION_SCHEMA_VERSION,
                "event-callback",
                ORDER_ID,
                RUN_ID,
                OPERATION,
                WorkerAttemptProofs.create(
                        ATTEMPT_TOKEN, "event-callback", OPERATION, RUN_ID),
                WorkerRunStatus.FAILED,
                Optional.empty(),
                "PROVIDER_FAILURE");
        byte[] body = "callback-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ContentHash bodyHash = hash(body);
        ArtifactReference bodyRef = new ArtifactReference("artifact:callback-body");
        WorkerCallbackInboxEntry received = WorkerCallbackInboxEntry.received(
                "callback-id", TENANT, "codex-worker", payload.eventId(), ORDER_ID, RUN_ID,
                OPERATION, WorkerDispatchOperationIds.hashAttemptToken(ATTEMPT_TOKEN),
                bodyRef, bodyHash, receivedAt);
        InMemoryInbox inbox = new InMemoryInbox(received);
        WorkerProtocolArtifacts protocolArtifacts = new WorkerProtocolArtifacts(
                artifactStore(new Artifact(
                        TENANT, bodyRef, bodyHash, "application/json", body)),
                decoder(payload));
        InMemoryRunStore runStore = new InMemoryRunStore();
        runStore.create(actionRun(deadline, ActionRunStatus.CANCELLED));
        InMemoryWorkerRuns workerRuns = new InMemoryWorkerRuns(cancelRequested);
        WorkerCallbackProcessor processor = new WorkerCallbackProcessor(
                inbox,
                protocolArtifacts,
                workOrders(order),
                workerRuns,
                runStore,
                new CandidateIngestionService(protocolArtifacts, emptyCandidates()),
                new WorkerCompletionService(workerRuns, unusedRuntime(), clock),
                new ConservativeWorkerCompletionClassifier(),
                clock,
                Duration.ofMinutes(1),
                Duration.ofSeconds(1));

        assertEquals(true, processor.tickOnce());
        assertEquals(WorkerCallbackInboxStatus.SUPERSEDED, inbox.current().status());
        assertEquals(WorkerRunStatus.CANCEL_REQUESTED, workerRuns.current().status());
    }

    private static WorkOrder workOrder(Instant deadline) {
        return new WorkOrder(
                ORDER_ID,
                TENANT,
                SESSION,
                "generate-candidate",
                "Generate candidate",
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new ArtifactReference("artifact:instruction"),
                hash("instruction".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new ArtifactReference("artifact:input"),
                hash("input".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "workspace:callback",
                java.util.List.of("src"),
                java.util.List.of("src"),
                Set.of(),
                "factory.candidate.v1",
                "1",
                new ArtifactReference("artifact:policy"),
                deadline,
                1,
                "logical-callback",
                WorkOrderCreatorType.SYSTEM,
                "test",
                START);
    }

    private static WorkerRunRecord requested(Instant deadline) {
        return new WorkerRunRecord(
                RUN_ID, TENANT, SESSION, ORDER_ID, 1, "codex-worker", "1",
                new WorkerCapabilities(Set.of()), WorkerRunStatus.REQUESTED, Optional.empty(),
                OPERATION, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                deadline, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, START, START);
    }

    private static ActionRun actionRun(Instant deadline) {
        return actionRun(deadline, ActionRunStatus.RUNNING);
    }

    private static ActionRun actionRun(Instant deadline, ActionRunStatus status) {
        return ActionRun.builder()
                .runId(ACTION_RUN)
                .tenantId(TENANT.value())
                .actionId(WorkerDispatchAction.ACTION_ID)
                .input(Map.of(
                        WorkerDispatchAction.WORK_ORDER_ID, ORDER_ID.value(),
                        WorkerDispatchAction.WORKER_RUN_ID, RUN_ID.value(),
                        WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION, 0L))
                .status(status)
                .attemptToken(ATTEMPT_TOKEN)
                .externalOperationId(OPERATION)
                .dueAt(deadline)
                .createdAt(START)
                .updatedAt(START.plusSeconds(1))
                .build();
    }

    private static ArtifactStore artifactStore(Artifact artifact) {
        return new ArtifactStore() {
            @Override public ArtifactReference store(Artifact value) { throw new UnsupportedOperationException(); }
            @Override public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
                return artifact.tenantId().equals(tenantId) && artifact.reference().equals(reference)
                        ? Optional.of(artifact) : Optional.empty();
            }
        };
    }

    private static WorkerProtocolArtifactDecoder decoder(CodingWorkerCompletionPayload payload) {
        return new WorkerProtocolArtifactDecoder() {
            @Override public CodingWorkerCompletionPayload decodeCompletionPayload(byte[] content) {
                return payload;
            }
            @Override public CodingWorkerInputManifest decodeInputManifest(byte[] content) {
                throw new UnsupportedOperationException();
            }
            @Override public CandidateSourceManifest decodeCandidateSourceManifest(byte[] content) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static WorkOrderRepository workOrders(WorkOrder order) {
        return new WorkOrderRepository() {
            @Override public void create(WorkOrder value) { throw new UnsupportedOperationException(); }
            @Override public Optional<WorkOrder> find(TenantId tenantId, WorkOrderId workOrderId) {
                return order.tenantId().equals(tenantId) && order.workOrderId().equals(workOrderId)
                        ? Optional.of(order) : Optional.empty();
            }
        };
    }

    private static CandidateVersionRepository emptyCandidates() {
        return new CandidateVersionRepository() {
            @Override public void create(CandidateVersion candidateVersion) { throw new UnsupportedOperationException(); }
            @Override public Optional<CandidateVersion> find(TenantId tenantId, CandidateId candidateId) {
                return Optional.empty();
            }
            @Override public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                    TenantId tenantId, BuildSessionId buildSessionId, WorkOrderId workOrderId) {
                return Optional.empty();
            }
        };
    }

    private static CompletableActionRuntime unusedRuntime() {
        return new CompletableActionRuntime() {
            @Override public ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context) {
                throw new AssertionError("not called");
            }
            @Override public ActionExecutionResult resume(String runId, ApprovalDecision decision) {
                throw new AssertionError("not called");
            }
            @Override public ActionExecutionResult complete(
                    String runId, String attemptToken, ActionExecutionResult result) {
                throw new AssertionError("not called");
            }
            @Override public ActionExecutionResult cancel(String runId, String reason) {
                throw new AssertionError("not called");
            }
        };
    }

    private static final class RecordingRuntime implements CompletableActionRuntime {
        private final AtomicInteger completions = new AtomicInteger();
        @Override public ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context) {
            throw new UnsupportedOperationException();
        }
        @Override public ActionExecutionResult resume(String runId, ApprovalDecision decision) {
            throw new UnsupportedOperationException();
        }
        @Override public ActionExecutionResult complete(
                String runId, String attemptToken, ActionExecutionResult result) {
            completions.incrementAndGet();
            return result;
        }
        @Override public ActionExecutionResult cancel(String runId, String reason) {
            throw new UnsupportedOperationException();
        }
    }

    private static ContentHash hash(byte[] bytes) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class InMemoryInbox implements WorkerCallbackInboxRepository {
        private final AtomicReference<WorkerCallbackInboxEntry> value;

        private InMemoryInbox(WorkerCallbackInboxEntry initial) {
            value = new AtomicReference<>(initial);
        }

        WorkerCallbackInboxEntry current() { return value.get(); }
        @Override public void create(WorkerCallbackInboxEntry entry) { throw new UnsupportedOperationException(); }
        @Override public Optional<WorkerCallbackInboxEntry> find(TenantId tenantId, String callbackId) {
            WorkerCallbackInboxEntry current = value.get();
            return current.tenantId().equals(tenantId) && current.callbackId().equals(callbackId)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<WorkerCallbackInboxEntry> findByEvent(
                TenantId tenantId, String workerBindingId, String eventId) {
            return Optional.empty();
        }
        @Override public Optional<WorkerCallbackInboxEntry> claimNext(
                Instant now, Duration lease, String claimToken) {
            WorkerCallbackInboxEntry current = value.get();
            WorkerCallbackInboxEntry claimed = current.claim(claimToken, now, lease);
            return value.compareAndSet(current, claimed) ? Optional.of(claimed) : Optional.empty();
        }
        @Override public Optional<WorkerCallbackInboxEntry> claimExpired(
                Instant now, Duration lease, String claimToken) {
            WorkerCallbackInboxEntry current = value.get();
            if (current.status() != WorkerCallbackInboxStatus.PROCESSING
                    || current.leaseUntil().filter(value -> !now.isBefore(value)).isEmpty()) {
                return Optional.empty();
            }
            WorkerCallbackInboxEntry claimed = current.claim(claimToken, now, lease);
            return value.compareAndSet(current, claimed) ? Optional.of(claimed) : Optional.empty();
        }
        @Override public boolean compareAndSet(
                WorkerCallbackInboxEntry expected, WorkerCallbackInboxEntry next) {
            return value.compareAndSet(expected, next);
        }
    }

    private static final class InMemoryWorkerRuns implements WorkerRunRepository {
        private final AtomicReference<WorkerRunRecord> value;

        private InMemoryWorkerRuns(WorkerRunRecord initial) {
            value = new AtomicReference<>(initial);
        }

        WorkerRunRecord current() { return value.get(); }
        @Override public void create(WorkerRunRecord run) { throw new UnsupportedOperationException(); }
        @Override public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId) {
            WorkerRunRecord current = value.get();
            return current.tenantId().equals(tenantId) && current.workerRunId().equals(workerRunId)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
            return value.compareAndSet(expected, next);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
