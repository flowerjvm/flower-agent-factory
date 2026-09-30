package io.github.flowerjvm.factory.application.work;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateIngestionService;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxRepository;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.DispatchOutboxId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.CodingWorker;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkOrderCreatorType;
import io.github.flowerjvm.factory.contracts.worker.WorkerAttemptToken;
import io.github.flowerjvm.factory.contracts.worker.WorkerCancelRequest;
import io.github.flowerjvm.factory.contracts.worker.WorkerCancelResult;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerDispatchException;
import io.github.flowerjvm.factory.contracts.worker.WorkerDispatchRequest;
import io.github.flowerjvm.factory.contracts.worker.WorkerEffectCertainty;
import io.github.flowerjvm.factory.contracts.worker.WorkerOperationNotFoundException;
import io.github.flowerjvm.factory.contracts.worker.WorkerProtocol;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunSnapshot;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerStatusObservation;
import io.github.flowerjvm.factory.contracts.worker.WorkerStatusRequest;
import io.github.flowerjvm.factory.contracts.worker.WorkerSubmission;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.CompletableActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalDecision;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkerPublisherCancellationBoundaryTest {
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC);
    private static final TenantId TENANT = new TenantId("tenant-cancel");
    private static final BuildSessionId SESSION = new BuildSessionId("session-cancel");
    private static final WorkOrderId ORDER_ID = new WorkOrderId("order-cancel");
    private static final WorkerRunId RUN_ID = new WorkerRunId("run-cancel");
    private static final DispatchOutboxId DISPATCH_ID = new DispatchOutboxId("dispatch-cancel");
    private static final String OPERATION = "operation-cancel";
    private static final String ACTION_ID = "action-run-cancel";
    private static final String ATTEMPT_TOKEN = "cancel-attempt-token";

    @Test
    void canonicalActionCancellationClosesPendingDispatchWithoutWorkerSubmit() {
        WorkOrder order = workOrder();
        WorkerRunRecord run = cancelRequestedRun();
        InMemoryRunStore actions = actions(ActionRunStatus.CANCELLED);
        InMemoryOutboxes outboxes = new InMemoryOutboxes(
                pending(WorkerOutboxOperations.DISPATCH, DISPATCH_ID));
        RecordingWorker worker = new RecordingWorker(false);
        WorkerRunRepository workerRuns = workerRuns(run);
        WorkerDispatchPublisher publisher = new WorkerDispatchPublisher(
                outboxes,
                dispatchStarts(outboxes),
                (observedAt, grace) -> false,
                workOrders(order),
                workerRuns,
                actions,
                worker,
                (tenantId, workerRunId, outboxId, operationId, claimToken,
                        externalSessionRef, acceptedAt, recordedAt) -> {
                    throw new AssertionError("dispatch acceptance must not run after cancellation");
                },
                (tenantId, bindingId, payload, observedAt) -> {
                    throw new AssertionError("completion staging must not run after cancellation");
                },
                emptyInbox(),
                new WorkerDispatchUncertaintyService(workerRuns, actions, unusedRuntime()),
                CLOCK,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10));

        assertEquals(true, publisher.tickOnce());
        assertEquals(0, worker.submitCalls.get());
        assertEquals(DispatchOutboxStatus.MANUAL_REVIEW, outboxes.current().status());
        assertEquals(
                Optional.of(WorkerDispatchPublisher.CANCELLED_BEFORE_SUBMIT),
                outboxes.current().lastCode());
    }

    @Test
    void atomicCancellationStartBoundaryLoserInvokesNoExternalSubmit() {
        WorkOrder order = workOrder();
        WorkerRunRecord run = dispatchingRun(order.deadlineAt());
        InMemoryRunStore actions = actions(ActionRunStatus.WAITING_EXTERNAL);
        InMemoryOutboxes outboxes = new InMemoryOutboxes(
                pending(WorkerOutboxOperations.DISPATCH, DISPATCH_ID));
        RecordingWorker worker = new RecordingWorker(false);
        WorkerRunRepository workerRuns = workerRuns(run);
        AtomicInteger startClaims = new AtomicInteger();
        WorkerDispatchPublisher publisher = new WorkerDispatchPublisher(
                outboxes,
                (now, lease, token) -> {
                    startClaims.incrementAndGet();
                    DispatchOutbox current = outboxes.current();
                    DispatchOutbox claimed = current.claimForSubmission(token, now, lease);
                    DispatchOutbox blocked = claimed.manualReview(
                            token, WorkerDispatchPublisher.CANCELLED_BEFORE_SUBMIT, now);
                    return outboxes.compareAndSet(current, blocked)
                            ? Optional.of(blocked) : Optional.empty();
                },
                (observedAt, grace) -> false,
                workOrders(order),
                workerRuns,
                actions,
                worker,
                (tenantId, workerRunId, outboxId, operationId, claimToken,
                        externalSessionRef, acceptedAt, recordedAt) -> {
                    throw new AssertionError("a start-boundary loser cannot be accepted");
                },
                (tenantId, bindingId, payload, observedAt) -> {
                    throw new AssertionError("a start-boundary loser has no completion");
                },
                emptyInbox(),
                new WorkerDispatchUncertaintyService(workerRuns, actions, unusedRuntime()),
                CLOCK,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10));

        assertEquals(true, publisher.tickOnce());
        assertEquals(1, startClaims.get());
        assertEquals(0, worker.submitCalls.get());
        assertEquals(DispatchOutboxStatus.MANUAL_REVIEW, outboxes.current().status());
        assertEquals(Optional.of(WorkerDispatchPublisher.CANCELLED_BEFORE_SUBMIT),
                outboxes.current().lastCode());
    }

    @Test
    void restartPreParkCrashWaitsForGraceThenSurfacesDurableManualReviewWithoutSubmit() {
        WorkOrder order = workOrder();
        AtomicReference<WorkerRunRecord> runValue = new AtomicReference<>(
                dispatchingRun(order.deadlineAt()));
        WorkerRunRepository workerRuns = new WorkerRunRepository() {
            @Override public void create(WorkerRunRecord run) { throw new UnsupportedOperationException(); }
            @Override public Optional<WorkerRunRecord> find(
                    TenantId tenantId, WorkerRunId workerRunId) {
                WorkerRunRecord current = runValue.get();
                return current.tenantId().equals(tenantId)
                                && current.workerRunId().equals(workerRunId)
                        ? Optional.of(current) : Optional.empty();
            }
            @Override public boolean compareAndSet(
                    WorkerRunRecord expected, WorkerRunRecord next) {
                return runValue.compareAndSet(expected, next);
            }
        };
        InMemoryRunStore actions = actions(ActionRunStatus.RUNNING);
        ActionRun initiallyRunning = actions.find(ACTION_ID).orElseThrow();
        ActionRun exactPrePark = initiallyRunning.toBuilder()
                .version(initiallyRunning.version() + 1)
                .currentStage("execute-action")
                .externalOperationId("")
                .result(null)
                .build();
        assertEquals(true, actions.compareAndSet(initiallyRunning, exactPrePark));
        InMemoryOutboxes outboxes = new InMemoryOutboxes(
                pending(WorkerOutboxOperations.DISPATCH, DISPATCH_ID));
        AtomicReference<BuildSession> session = new AtomicReference<>(runningSession());
        AtomicInteger submitCalls = new AtomicInteger();
        CodingWorker worker = new CodingWorker() {
            @Override public WorkerCapabilities capabilities() {
                throw new AssertionError("pre-park orphan must not inspect the adapter");
            }
            @Override public WorkerSubmission submit(WorkerDispatchRequest request) {
                submitCalls.incrementAndGet();
                throw new AssertionError("pre-park orphan must never submit");
            }
            @Override public WorkerStatusObservation status(WorkerStatusRequest request) {
                throw new AssertionError("fresh pre-park orphan has no external operation to poll");
            }
            @Override public WorkerCancelResult cancel(WorkerCancelRequest request) {
                throw new AssertionError("pre-park orphan must not cancel");
            }
        };
        WorkerDispatchStartTransaction start = (now, lease, claimToken) -> {
            assertEquals(ActionRunStatus.RUNNING,
                    actions.find(ACTION_ID).orElseThrow().status());
            return Optional.empty();
        };
        WorkerDispatchPreParkOrphanTransaction preParkOrphans = (observedAt, grace) -> {
            Instant staleAt = exactPrePark.updatedAt().plus(grace);
            if (observedAt.isBefore(staleAt)) {
                return false;
            }
            assertEquals(WorkerRunStatus.DISPATCHING, runValue.get().status());
            assertEquals(ActionRunStatus.RUNNING, actions.find(ACTION_ID).orElseThrow().status());
            DispatchOutbox fresh = outboxes.current();
            BuildSession running = session.get();
            assertEquals(true, outboxes.compareAndSet(
                    fresh,
                    fresh.manualReviewBeforeSubmit(
                            WorkerDispatchPublisher.ACTION_PARK_ORPHANED, observedAt)));
            assertEquals(true, session.compareAndSet(
                    running,
                    running.manualReview(
                            WorkerDispatchPublisher.ACTION_PARK_ORPHANED,
                            "Action dispatch did not durably enter its deferred wait state",
                            observedAt)));
            return true;
        };
        AtomicReference<Instant> clockValue = new AtomicReference<>(
                exactPrePark.updatedAt().plus(WorkerDispatchPublisher.DEFAULT_PRE_PARK_GRACE)
                        .minusNanos(1));
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return clockValue.get(); }
        };
        WorkerDispatchPublisher publisher = new WorkerDispatchPublisher(
                outboxes, start, preParkOrphans, workOrders(order), workerRuns, actions, worker,
                (tenantId, workerRunId, outboxId, operationId, claimToken,
                        externalSessionRef, acceptedAt, recordedAt) -> {
                    throw new AssertionError("pre-park orphan cannot be accepted");
                },
                (tenantId, bindingId, payload, trustedReceivedAt) -> {
                    throw new AssertionError("pre-park orphan has no completion payload");
                },
                emptyInbox(),
                new WorkerDispatchUncertaintyService(
                        workerRuns, actions, unusedRuntime()),
                clock, Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(10));

        assertEquals(false, publisher.tickOnce());
        assertEquals(0, submitCalls.get());
        assertEquals(ActionRunStatus.RUNNING,
                actions.find(ACTION_ID).orElseThrow().status());
        assertEquals(DispatchOutboxStatus.PENDING, outboxes.current().status());
        assertEquals(BuildSessionStatus.RUNNING, session.get().status());

        clockValue.set(exactPrePark.updatedAt().plus(
                WorkerDispatchPublisher.DEFAULT_PRE_PARK_GRACE));
        assertEquals(true, publisher.tickOnce());
        assertEquals(0, submitCalls.get());
        assertEquals(ActionRunStatus.RUNNING,
                actions.find(ACTION_ID).orElseThrow().status());
        assertEquals(WorkerRunStatus.DISPATCHING, runValue.get().status());
        assertEquals(DispatchOutboxStatus.MANUAL_REVIEW, outboxes.current().status());
        assertEquals(Optional.of(WorkerDispatchPublisher.ACTION_PARK_ORPHANED),
                outboxes.current().lastCode());
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, session.get().status());
        assertEquals(Optional.of(WorkerDispatchPublisher.ACTION_PARK_ORPHANED),
                session.get().terminalCode());
    }

    @Test
    void cancellingPreParkCrashConvergesAfterGraceWithoutExternalWorkerCall() {
        WorkOrder order = workOrder();
        WorkerRunRecord run = dispatchingRun(order.deadlineAt());
        WorkerRunRepository workerRuns = workerRuns(run);
        InMemoryRunStore actions = actions(ActionRunStatus.RUNNING);
        ActionRun initial = actions.find(ACTION_ID).orElseThrow();
        ActionRun exactPrePark = initial.toBuilder()
                .version(initial.version() + 1)
                .currentStage("execute-action")
                .externalOperationId("")
                .result(null)
                .build();
        assertEquals(true, actions.compareAndSet(initial, exactPrePark));
        InMemoryOutboxes outboxes = new InMemoryOutboxes(
                pending(WorkerOutboxOperations.DISPATCH, DISPATCH_ID));
        AtomicReference<BuildSession> session = new AtomicReference<>(
                runningSession().requestCancellation(NOW.plusSeconds(2)));
        RecordingWorker worker = new RecordingWorker(false);
        Instant boundary = exactPrePark.updatedAt().plus(
                WorkerDispatchPublisher.DEFAULT_PRE_PARK_GRACE);
        Clock clock = Clock.fixed(boundary, ZoneOffset.UTC);
        WorkerDispatchPreParkOrphanTransaction preParkOrphans = (observedAt, grace) -> {
            assertEquals(boundary, observedAt);
            assertEquals(WorkerDispatchPublisher.DEFAULT_PRE_PARK_GRACE, grace);
            assertEquals(BuildSessionStatus.CANCELLING, session.get().status());
            assertEquals(WorkerRunStatus.DISPATCHING,
                    workerRuns.find(TENANT, RUN_ID).orElseThrow().status());
            assertEquals(ActionRunStatus.RUNNING,
                    actions.find(ACTION_ID).orElseThrow().status());
            DispatchOutbox fresh = outboxes.current();
            BuildSession cancelling = session.get();
            assertEquals(true, outboxes.compareAndSet(
                    fresh,
                    fresh.manualReviewBeforeSubmit(
                            WorkerDispatchPublisher.ACTION_PARK_CANCEL_ORPHANED,
                            observedAt)));
            assertEquals(true, session.compareAndSet(
                    cancelling,
                    cancelling.manualReviewCancellation(
                            WorkerDispatchPublisher.ACTION_PARK_CANCEL_ORPHANED,
                            "Cancellation could not enter the deferred Worker hook",
                            observedAt)));
            return true;
        };
        WorkerDispatchPublisher publisher = new WorkerDispatchPublisher(
                outboxes,
                (observedAt, lease, claimToken) -> {
                    throw new AssertionError("a RUNNING pre-park orphan cannot be claimed");
                },
                preParkOrphans,
                workOrders(order),
                workerRuns,
                actions,
                worker,
                (tenantId, workerRunId, outboxId, operationId, claimToken,
                        externalSessionRef, acceptedAt, recordedAt) -> {
                    throw new AssertionError("pre-park cancellation cannot be accepted");
                },
                (tenantId, bindingId, payload, trustedReceivedAt) -> {
                    throw new AssertionError("pre-park cancellation has no completion");
                },
                emptyInbox(),
                new WorkerDispatchUncertaintyService(workerRuns, actions, unusedRuntime()),
                clock,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10));

        assertEquals(true, publisher.tickOnce());
        assertEquals(0, worker.submitCalls.get());
        assertEquals(0, worker.statusCalls.get());
        assertEquals(0, worker.cancelCalls.get());
        assertEquals(DispatchOutboxStatus.MANUAL_REVIEW, outboxes.current().status());
        assertEquals(Optional.of(WorkerDispatchPublisher.ACTION_PARK_CANCEL_ORPHANED),
                outboxes.current().lastCode());
        assertEquals(BuildSessionStatus.MANUAL_REVIEW, session.get().status());
        assertEquals(Optional.of(WorkerDispatchPublisher.ACTION_PARK_CANCEL_ORPHANED),
                session.get().terminalCode());
        assertEquals(WorkerRunStatus.DISPATCHING,
                workerRuns.find(TENANT, RUN_ID).orElseThrow().status());
        assertEquals(ActionRunStatus.RUNNING,
                actions.find(ACTION_ID).orElseThrow().status());
    }

    @Test
    void capabilityCredentialRejectionBeforeSubmitConvergesAsProvenNoEffect() {
        WorkOrder order = workOrder();
        WorkerRunRecord run = dispatchingRun(order.deadlineAt());
        WorkerRunRepository workerRuns = workerRuns(run);
        InMemoryRunStore actions = actions(ActionRunStatus.WAITING_EXTERNAL);
        InMemoryOutboxes outboxes = new InMemoryOutboxes(
                pending(WorkerOutboxOperations.DISPATCH, DISPATCH_ID));
        AtomicInteger submitCalls = new AtomicInteger();
        AtomicInteger statusCalls = new AtomicInteger();
        CodingWorker worker = new CodingWorker() {
            @Override public WorkerCapabilities capabilities() {
                throw new WorkerDispatchException(
                        "WORKER_CREDENTIAL_SCOPE_REJECTED",
                        WorkerEffectCertainty.NO_EFFECT,
                        WorkerRetryDisposition.AFTER_CORRECTION);
            }
            @Override public WorkerSubmission submit(WorkerDispatchRequest request) {
                submitCalls.incrementAndGet();
                throw new AssertionError("credential rejection must precede submit");
            }
            @Override public WorkerStatusObservation status(WorkerStatusRequest request) {
                statusCalls.incrementAndGet();
                throw new AssertionError("proven no-effect rejection must not enter status recovery");
            }
            @Override public WorkerCancelResult cancel(WorkerCancelRequest request) {
                throw new AssertionError("dispatch credential rejection must not cancel");
            }
        };
        WorkerDispatchPublisher publisher = new WorkerDispatchPublisher(
                outboxes,
                dispatchStarts(outboxes),
                (observedAt, grace) -> false,
                workOrders(order),
                workerRuns,
                actions,
                worker,
                (tenantId, workerRunId, outboxId, operationId, claimToken,
                        externalSessionRef, acceptedAt, recordedAt) -> {
                    throw new AssertionError("rejected capability lookup cannot be accepted");
                },
                (tenantId, bindingId, payload, trustedReceivedAt) -> {
                    throw new AssertionError("rejected capability lookup has no completion");
                },
                emptyInbox(),
                new WorkerDispatchUncertaintyService(
                        workerRuns, actions, completingRuntime(actions, CLOCK.instant())),
                CLOCK,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10));

        assertEquals(true, publisher.tickOnce());
        assertEquals(0, submitCalls.get());
        assertEquals(0, statusCalls.get());
        assertEquals(DispatchOutboxStatus.MANUAL_REVIEW, outboxes.current().status());
        assertEquals(Optional.of("WORKER_CREDENTIAL_SCOPE_REJECTED"),
                outboxes.current().lastCode());
        assertEquals(WorkerRunStatus.MANUAL_REVIEW,
                workerRuns.find(TENANT, RUN_ID).orElseThrow().status());
        assertEquals(ActionRunStatus.FAILED,
                actions.find(ACTION_ID).orElseThrow().status());
    }

    @Test
    void cancelledActionReconcilesExpiredInFlightDispatchInsteadOfAssumingNoSubmit() {
        WorkOrder order = workOrder();
        WorkerRunRecord run = cancelRequestedRun();
        InMemoryRunStore actions = actions(ActionRunStatus.CANCELLED);
        InMemoryOutboxes outboxes = new InMemoryOutboxes(
                pending(WorkerOutboxOperations.DISPATCH, DISPATCH_ID)
                        .claimForSubmission("abandoned-in-flight", NOW, Duration.ofSeconds(1)));
        AtomicInteger statusCalls = new AtomicInteger();
        CodingWorker worker = new CodingWorker() {
            @Override public WorkerCapabilities capabilities() { return new WorkerCapabilities(Set.of()); }
            @Override public WorkerSubmission submit(WorkerDispatchRequest request) {
                throw new AssertionError("expired in-flight dispatch must never be resubmitted");
            }
            @Override public WorkerStatusObservation status(WorkerStatusRequest request) {
                statusCalls.incrementAndGet();
                return WorkerStatusObservation.found(new WorkerRunSnapshot(
                        TENANT, ORDER_ID, RUN_ID, OPERATION, WorkerRunStatus.CANCEL_REQUESTED,
                        Optional.of(NOW.plusMillis(2_500)), Optional.of("worker-session"),
                        Optional.empty(), Optional.of("WORKER_CANCEL_REQUESTED")));
            }
            @Override public WorkerCancelResult cancel(WorkerCancelRequest request) {
                throw new AssertionError("dispatch reconciliation must not issue cancel directly");
            }
        };
        AtomicInteger acceptances = new AtomicInteger();
        WorkerRunRepository workerRuns = workerRuns(run);
        WorkerDispatchPublisher publisher = new WorkerDispatchPublisher(
                outboxes,
                dispatchStarts(outboxes),
                (observedAt, grace) -> false,
                workOrders(order),
                workerRuns,
                actions,
                worker,
                (tenantId, workerRunId, outboxId, operationId, claimToken,
                        externalSessionRef, acceptedAt, recordedAt) -> {
                    acceptances.incrementAndGet();
                    DispatchOutbox current = outboxes.current();
                    return outboxes.compareAndSet(
                            current,
                            current.dispatched(
                                    claimToken, WorkerDispatchPublisher.DISPATCH_ACCEPTED, recordedAt));
                },
                (tenantId, bindingId, payload, observedAt) -> {
                    throw new AssertionError("nonterminal status has no completion payload");
                },
                emptyInbox(),
                new WorkerDispatchUncertaintyService(workerRuns, actions, unusedRuntime()),
                CLOCK,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10));

        assertEquals(true, publisher.tickOnce());
        assertEquals(1, statusCalls.get());
        assertEquals(1, acceptances.get());
        assertEquals(DispatchOutboxStatus.DISPATCHED, outboxes.current().status());
        assertEquals(WorkerRunStatus.CANCEL_REQUESTED,
                workerRuns.find(TENANT, RUN_ID).orElseThrow().status());
    }

    @Test
    void acceptedBeforeCommitRestartAfterDeadlineUsesDurableOnTimeReceipt() {
        Instant deadline = NOW.plusSeconds(2);
        Instant receivedAt = NOW.plusMillis(1_500);
        Clock restartClock = Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC);
        WorkOrder order = workOrder(deadline);
        WorkerRunRecord dispatching = dispatchingRun(deadline);
        AtomicReference<WorkerRunRecord> runValue = new AtomicReference<>(dispatching);
        WorkerRunRepository workerRuns = new WorkerRunRepository() {
            @Override public void create(WorkerRunRecord run) { throw new UnsupportedOperationException(); }
            @Override public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId) {
                WorkerRunRecord current = runValue.get();
                return current.tenantId().equals(tenantId) && current.workerRunId().equals(workerRunId)
                        ? Optional.of(current) : Optional.empty();
            }
            @Override public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
                return runValue.compareAndSet(expected, next);
            }
        };
        InMemoryRunStore actions = actions(ActionRunStatus.WAITING_EXTERNAL, deadline);
        DispatchOutbox abandoned = pending(WorkerOutboxOperations.DISPATCH, DISPATCH_ID)
                .claimForSubmission("abandoned-dispatch", NOW, Duration.ofSeconds(3));
        InMemoryOutboxes outboxes = new InMemoryOutboxes(abandoned);
        CodingWorkerCompletionPayload payload = new CodingWorkerCompletionPayload(
                WorkerProtocol.COMPLETION_SCHEMA_VERSION,
                "event-late-acceptance",
                ORDER_ID,
                RUN_ID,
                OPERATION,
                WorkerAttemptProofs.create(
                        ATTEMPT_TOKEN, "event-late-acceptance", OPERATION, RUN_ID),
                WorkerRunStatus.FAILED,
                Optional.empty(),
                "PROVIDER_FAILURE");
        WorkerCallbackInboxEntry received = WorkerCallbackInboxEntry.received(
                "callback-late-acceptance",
                TENANT,
                "codex-worker",
                payload.eventId(),
                ORDER_ID,
                RUN_ID,
                OPERATION,
                WorkerDispatchOperationIds.hashAttemptToken(ATTEMPT_TOKEN),
                new ArtifactReference("artifact:callback-late-acceptance"),
                hash("c"),
                receivedAt);
        WorkerCallbackInboxEntry orphaned = received
                .claim("callback-processor", deadline, Duration.ofSeconds(30))
                .manualReview(
                        "callback-processor",
                        WorkerCallbackProcessor.ACCEPTANCE_ORPHANED,
                        deadline);
        SingleInbox callbackInbox = new SingleInbox(orphaned);
        AtomicInteger statusCalls = new AtomicInteger();
        CodingWorker worker = new CodingWorker() {
            @Override public WorkerCapabilities capabilities() { return new WorkerCapabilities(Set.of()); }
            @Override public WorkerSubmission submit(WorkerDispatchRequest request) {
                throw new AssertionError("an expired dispatch claim must not resubmit");
            }
            @Override public WorkerStatusObservation status(WorkerStatusRequest request) {
                statusCalls.incrementAndGet();
                return WorkerStatusObservation.terminal(
                        new WorkerRunSnapshot(
                                TENANT, ORDER_ID, RUN_ID, OPERATION, WorkerRunStatus.FAILED,
                                Optional.of("worker-session"), Optional.empty(),
                                Optional.of("WORKER_FAILED")),
                        payload);
            }
            @Override public WorkerCancelResult cancel(WorkerCancelRequest request) {
                throw new AssertionError("dispatch recovery must not cancel");
            }
        };
        WorkerDispatchAcceptanceTransaction acceptance = (
                tenantId, workerRunId, outboxId, operationId, claimToken,
                externalSessionRef, acceptedAt, recordedAt) -> {
            assertEquals(receivedAt, acceptedAt);
            assertEquals(restartClock.instant(), recordedAt);
            WorkerRunRecord currentRun = runValue.get();
            DispatchOutbox currentOutbox = outboxes.current();
            WorkerRunRecord waiting = currentRun.awaitExternal(externalSessionRef, acceptedAt);
            DispatchOutbox dispatched = currentOutbox.dispatched(
                    claimToken, WorkerDispatchPublisher.DISPATCH_ACCEPTED, recordedAt);
            return runValue.compareAndSet(currentRun, waiting)
                    && outboxes.compareAndSet(currentOutbox, dispatched);
        };
        WorkerDispatchPublisher publisher = new WorkerDispatchPublisher(
                outboxes,
                dispatchStarts(outboxes),
                (observedAt, grace) -> false,
                workOrders(order),
                workerRuns,
                actions,
                worker,
                acceptance,
                (tenantId, bindingId, completion, observedAt) -> {
                    throw new AssertionError("the existing durable callback must be reused");
                },
                callbackInbox,
                new WorkerDispatchUncertaintyService(workerRuns, actions, unusedRuntime()),
                restartClock,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10));

        assertEquals(true, publisher.tickOnce());
        assertEquals(1, statusCalls.get());
        assertEquals(WorkerRunStatus.WAITING_EXTERNAL, runValue.get().status());
        assertEquals(receivedAt, runValue.get().updatedAt());
        assertEquals(DispatchOutboxStatus.DISPATCHED, outboxes.current().status());
        assertEquals(restartClock.instant(), outboxes.current().updatedAt());
        assertEquals(WorkerCallbackInboxStatus.RECEIVED, callbackInbox.current().status());
        assertEquals(Optional.of(WorkerCallbackProcessor.AWAITING_ACCEPTANCE),
                callbackInbox.current().lastCode());
    }

    @Test
    void statusOnlyAcceptedBeforeCommitRecoveryUsesTrustedJournalAcceptanceAfterDeadline() {
        Instant acceptedAt = NOW.plusMillis(1_250);

        DispatchRecoveryResult recovered = recoverStatusOnlyAfterDeadline(
                Optional.of(acceptedAt), Optional.of(NOW.plusMillis(1_500)));

        assertEquals(1, recovered.statusCalls());
        assertEquals(1, recovered.acceptanceCalls());
        assertEquals(1, recovered.stagedCompletions());
        assertEquals(WorkerRunStatus.WAITING_EXTERNAL, recovered.workerRun().status());
        assertEquals(acceptedAt, recovered.workerRun().updatedAt());
        assertEquals(DispatchOutboxStatus.DISPATCHED, recovered.outbox().status());
    }

    @Test
    void statusOnlyTerminalJournalTimeCompletesActionAndWorkerAfterDeadlineRestart() {
        Instant acceptedAt = NOW.plusMillis(1_250);
        Instant terminalAt = NOW.plusMillis(1_500);
        Instant deadline = NOW.plusSeconds(2);
        Instant restartAt = NOW.plusSeconds(3);
        Clock restartClock = Clock.fixed(restartAt, ZoneOffset.UTC);
        WorkOrder order = workOrder(deadline);
        AtomicReference<WorkerRunRecord> runValue = new AtomicReference<>(dispatchingRun(deadline));
        WorkerRunRepository workerRuns = new WorkerRunRepository() {
            @Override public void create(WorkerRunRecord run) { throw new UnsupportedOperationException(); }
            @Override public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId) {
                WorkerRunRecord current = runValue.get();
                return current.tenantId().equals(tenantId) && current.workerRunId().equals(workerRunId)
                        ? Optional.of(current) : Optional.empty();
            }
            @Override public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
                return runValue.compareAndSet(expected, next);
            }
        };
        InMemoryRunStore actions = actions(ActionRunStatus.WAITING_EXTERNAL, deadline);
        InMemoryOutboxes outboxes = new InMemoryOutboxes(
                pending(WorkerOutboxOperations.DISPATCH, DISPATCH_ID)
                        .claimForSubmission("abandoned-status-terminal", NOW, Duration.ofSeconds(1)));
        CodingWorkerCompletionPayload payload = new CodingWorkerCompletionPayload(
                WorkerProtocol.COMPLETION_SCHEMA_VERSION,
                "event-status-terminal",
                ORDER_ID,
                RUN_ID,
                OPERATION,
                WorkerAttemptProofs.create(
                        ATTEMPT_TOKEN, "event-status-terminal", OPERATION, RUN_ID),
                WorkerRunStatus.FAILED,
                Optional.empty(),
                "PROVIDER_FAILURE");
        CodingWorker worker = new CodingWorker() {
            @Override public WorkerCapabilities capabilities() { return new WorkerCapabilities(Set.of()); }
            @Override public WorkerSubmission submit(WorkerDispatchRequest request) {
                throw new AssertionError("expired dispatch recovery must not resubmit");
            }
            @Override public WorkerStatusObservation status(WorkerStatusRequest request) {
                return WorkerStatusObservation.terminal(
                        new WorkerRunSnapshot(
                                TENANT, ORDER_ID, RUN_ID, OPERATION, WorkerRunStatus.FAILED,
                                Optional.of(acceptedAt), Optional.of(terminalAt),
                                Optional.of("worker-session"), Optional.empty(),
                                Optional.of("WORKER_FAILED")),
                        payload);
            }
            @Override public WorkerCancelResult cancel(WorkerCancelRequest request) {
                throw new AssertionError("dispatch recovery must not cancel");
            }
        };
        WorkerDispatchAcceptanceTransaction acceptance = (
                tenantId, workerRunId, outboxId, operationId, claimToken,
                externalSessionRef, accepted, recordedAt) -> {
            assertEquals(acceptedAt, accepted);
            WorkerRunRecord currentRun = runValue.get();
            DispatchOutbox currentOutbox = outboxes.current();
            return runValue.compareAndSet(
                            currentRun, currentRun.awaitExternal(externalSessionRef, accepted))
                    && outboxes.compareAndSet(
                            currentOutbox,
                            currentOutbox.dispatched(
                                    claimToken, WorkerDispatchPublisher.DISPATCH_ACCEPTED, recordedAt));
        };
        StagedInbox inbox = new StagedInbox();
        byte[] body = "trusted-status-terminal".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ContentHash bodyHash = sha256(body);
        ArtifactReference bodyRef = new ArtifactReference("artifact:trusted-status-terminal");
        AtomicReference<Artifact> stored = new AtomicReference<>();
        WorkerCompletionPayloadStager stager = (tenantId, bindingId, completion, trustedReceivedAt) -> {
            assertEquals(terminalAt, trustedReceivedAt);
            stored.set(new Artifact(tenantId, bodyRef, bodyHash, "application/json", body));
            inbox.create(WorkerCallbackInboxEntry.received(
                    "callback-status-terminal", tenantId, bindingId, completion.eventId(),
                    completion.workOrderId(), completion.workerRunId(), completion.operationId(),
                    WorkerDispatchOperationIds.hashAttemptToken(ATTEMPT_TOKEN),
                    bodyRef, bodyHash, trustedReceivedAt));
            return new WorkerCallbackReceipt(
                    WorkerCallbackReceipt.Status.ACCEPTED,
                    WorkerCallbackIngressService.RECEIVED,
                    Optional.of("callback-status-terminal"));
        };
        WorkerDispatchPublisher publisher = new WorkerDispatchPublisher(
                outboxes, dispatchStarts(outboxes), (observedAt, grace) -> false,
                workOrders(order), workerRuns, actions, worker,
                acceptance, stager, inbox,
                new WorkerDispatchUncertaintyService(
                        workerRuns, actions, completingRuntime(actions, restartAt)),
                restartClock, Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(10));

        assertEquals(true, publisher.tickOnce());
        assertEquals(terminalAt, inbox.current().receivedAt());
        WorkerProtocolArtifacts protocolArtifacts = new WorkerProtocolArtifacts(
                new ArtifactStore() {
                    @Override public ArtifactReference store(Artifact artifact) {
                        stored.set(artifact);
                        return artifact.reference();
                    }
                    @Override public Optional<Artifact> find(
                            TenantId tenantId, ArtifactReference reference) {
                        Artifact artifact = stored.get();
                        return artifact != null
                                        && artifact.tenantId().equals(tenantId)
                                        && artifact.reference().equals(reference)
                                ? Optional.of(artifact) : Optional.empty();
                    }
                },
                new WorkerProtocolArtifactDecoder() {
                    @Override public CodingWorkerCompletionPayload decodeCompletionPayload(byte[] content) {
                        return payload;
                    }
                    @Override public CodingWorkerInputManifest decodeInputManifest(byte[] content) {
                        throw new UnsupportedOperationException();
                    }
                    @Override public CandidateSourceManifest decodeCandidateSourceManifest(byte[] content) {
                        throw new UnsupportedOperationException();
                    }
                });
        CandidateVersionRepository candidates = new CandidateVersionRepository() {
            @Override public void create(CandidateVersion candidateVersion) {
                throw new AssertionError("failed completion must not create a candidate");
            }
            @Override public Optional<CandidateVersion> find(TenantId tenantId, CandidateId candidateId) {
                return Optional.empty();
            }
            @Override public Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
                    TenantId tenantId, BuildSessionId buildSessionId, WorkOrderId workOrderId) {
                return Optional.empty();
            }
        };
        WorkerCallbackProcessor processor = new WorkerCallbackProcessor(
                inbox, protocolArtifacts, workOrders(order), workerRuns, actions,
                new CandidateIngestionService(protocolArtifacts, candidates),
                new WorkerCompletionService(
                        workerRuns, completingRuntime(actions, restartAt), restartClock),
                new ConservativeWorkerCompletionClassifier(), restartClock,
                Duration.ofSeconds(30), Duration.ofSeconds(1));

        assertEquals(true, processor.tickOnce());
        assertEquals(WorkerCallbackInboxStatus.APPLIED, inbox.current().status());
        assertEquals(WorkerRunStatus.FAILED, runValue.get().status());
        assertEquals(Optional.of(terminalAt), runValue.get().completedAt());
        assertEquals(ActionRunStatus.FAILED, actions.find(ACTION_ID).orElseThrow().status());
    }

    @Test
    void missingStaleDeadlineAndFutureJournalAcceptanceTimesFailClosed() {
        List<Optional<Instant>> unsafeTimes = List.of(
                Optional.empty(),
                Optional.of(NOW.plusMillis(500)),
                Optional.of(NOW.plusSeconds(2)),
                Optional.of(NOW.plusMillis(2_500)),
                Optional.of(NOW.plusSeconds(4)));

        for (Optional<Instant> unsafeTime : unsafeTimes) {
            DispatchRecoveryResult rejected = recoverStatusOnlyAfterDeadline(
                    unsafeTime, Optional.empty());

            assertEquals(1, rejected.statusCalls());
            assertEquals(0, rejected.acceptanceCalls());
            assertEquals(0, rejected.stagedCompletions());
            assertEquals(WorkerRunStatus.MANUAL_REVIEW, rejected.workerRun().status());
            assertEquals(DispatchOutboxStatus.MANUAL_REVIEW, rejected.outbox().status());
            assertEquals(
                    Optional.of(WorkerDispatchPublisher.ACCEPTANCE_TIME_UNPROVEN),
                    rejected.outbox().lastCode());
        }
    }

    @Test
    void preSubmitCancelOperationNotFoundConfirmsZeroExternalEffect() {
        WorkOrder order = workOrder();
        WorkerRunRecord run = cancelRequestedRun();
        InMemoryRunStore actions = actions(ActionRunStatus.CANCELLED);
        DispatchOutboxId cancelId = new DispatchOutboxId("cancel-intent");
        InMemoryOutboxes outboxes = new InMemoryOutboxes(
                pending(WorkerOutboxOperations.CANCEL, cancelId));
        RecordingWorker worker = new RecordingWorker(true);
        AtomicInteger confirms = new AtomicInteger();
        WorkerCancellationOutcomeTransaction outcomes = new WorkerCancellationOutcomeTransaction() {
            @Override
            public boolean confirm(
                    TenantId tenantId,
                    WorkerRunId workerRunId,
                    DispatchOutboxId outboxId,
                    String operationId,
                    String claimToken,
                    String stableCode,
                    Instant confirmedAt) {
                confirms.incrementAndGet();
                DispatchOutbox current = outboxes.current();
                return outboxes.compareAndSet(
                        current, current.confirmed(claimToken, stableCode, confirmedAt));
            }

            @Override
            public boolean confirmAbsentAfterProvenNoDispatch(
                    TenantId tenantId,
                    WorkerRunId workerRunId,
                    DispatchOutboxId cancelOutboxId,
                    DispatchOutboxId dispatchOutboxId,
                    String operationId,
                    String claimToken,
                    String stableCode,
                    Instant confirmedAt) {
                return confirm(
                        tenantId, workerRunId, cancelOutboxId, operationId, claimToken,
                        stableCode, confirmedAt);
            }

            @Override
            public boolean manualReview(
                    TenantId tenantId,
                    WorkerRunId workerRunId,
                    DispatchOutboxId outboxId,
                    String operationId,
                    String claimToken,
                    String stableCode,
                    Instant observedAt) {
                throw new AssertionError("authoritative absence is not uncertain");
            }

            @Override
            public boolean supersedeAfterTerminalCompletion(
                    TenantId tenantId,
                    WorkerRunId workerRunId,
                    DispatchOutboxId outboxId,
                    String operationId,
                    String claimToken,
                    String stableCode,
                    Instant observedAt) {
                throw new AssertionError("authoritative absence is not a completion race");
            }
        };
        WorkerCancelPublisher publisher = new WorkerCancelPublisher(
                outboxes,
                workOrders(order),
                workerRuns(run),
                actions,
                worker,
                outcomes,
                unusedRuntime(),
                CLOCK,
                Duration.ofSeconds(30));

        assertEquals(true, publisher.tickOnce());
        assertEquals(1, worker.cancelCalls.get());
        assertEquals(1, confirms.get());
        assertEquals(DispatchOutboxStatus.CONFIRMED, outboxes.current().status());
        assertEquals(
                Optional.of(WorkerCancelPublisher.CANCEL_OPERATION_ABSENT),
                outboxes.current().lastCode());
    }

    @Test
    void expiredCancelClaimReplaysGovernedActionCancelThenConfirmsRemoteAbsence() {
        WorkOrder order = workOrder();
        WorkerRunRecord run = cancelRequestedRun();
        InMemoryRunStore actions = actions(ActionRunStatus.WAITING_EXTERNAL);
        DispatchOutbox abandoned = pending(
                        WorkerOutboxOperations.CANCEL,
                        new DispatchOutboxId("cancel-abandoned"))
                .claimForSubmission("abandoned-owner", NOW, Duration.ofSeconds(3));
        InMemoryOutboxes outboxes = new InMemoryOutboxes(abandoned);
        RecordingWorker worker = new RecordingWorker(false, true);
        RecordingOutcomes outcomes = new RecordingOutcomes(outboxes);
        WorkerCancelPublisher publisher = new WorkerCancelPublisher(
                outboxes, workOrders(order), workerRuns(run), actions, worker, outcomes,
                recoveringCancelRuntime(actions), CLOCK, Duration.ofSeconds(30));

        assertEquals(true, publisher.tickOnce());
        assertEquals(0, worker.cancelCalls.get());
        assertEquals(1, worker.statusCalls.get());
        assertEquals(1, outcomes.confirms.get());
        assertEquals(0, outcomes.manualReviews.get());
        assertEquals(ActionRunStatus.CANCELLED, actions.find(ACTION_ID).orElseThrow().status());
        assertEquals(DispatchOutboxStatus.CONFIRMED, outboxes.current().status());
        assertEquals(
                Optional.of(WorkerCancelPublisher.CANCEL_OPERATION_ABSENT),
                outboxes.current().lastCode());
    }

    @Test
    void submitInFlightNotFoundHandsOffOneCancelWhenTheLateEffectAppears() {
        WorkOrder order = workOrder();
        WorkerRunRecord run = cancelRequestedRun();
        InMemoryRunStore actions = actions(ActionRunStatus.CANCELLED);
        InMemoryOutboxes outboxes = new InMemoryOutboxes(
                pending(WorkerOutboxOperations.CANCEL, new DispatchOutboxId("cancel-in-flight")));
        AtomicInteger cancelCalls = new AtomicInteger();
        AtomicInteger statusCalls = new AtomicInteger();
        CodingWorker worker = new CodingWorker() {
            @Override public WorkerCapabilities capabilities() { return new WorkerCapabilities(Set.of()); }
            @Override public WorkerSubmission submit(WorkerDispatchRequest request) {
                throw new AssertionError("cancel publisher must not submit");
            }
            @Override public WorkerStatusObservation status(WorkerStatusRequest request) {
                statusCalls.incrementAndGet();
                return WorkerStatusObservation.found(new WorkerRunSnapshot(
                        TENANT, ORDER_ID, RUN_ID, OPERATION, WorkerRunStatus.WAITING_EXTERNAL,
                        Optional.of(NOW.plusMillis(2_500)), Optional.of("late-worker-session"),
                        Optional.empty(), Optional.of("WORKER_RUNNING")));
            }
            @Override public WorkerCancelResult cancel(WorkerCancelRequest request) {
                if (cancelCalls.incrementAndGet() == 1) {
                    throw new WorkerOperationNotFoundException();
                }
                return new WorkerCancelResult(RUN_ID, WorkerRunStatus.CANCELLED, "WORKER_CANCELLED");
            }
        };
        RecordingOutcomes outcomes = new RecordingOutcomes(outboxes, false);
        Duration lease = Duration.ofSeconds(1);
        WorkerCancelPublisher publisher = new WorkerCancelPublisher(
                outboxes, workOrders(order), workerRuns(run), actions, worker, outcomes,
                unusedRuntime(), CLOCK, lease);

        assertEquals(true, publisher.tickOnce());
        assertEquals(1, cancelCalls.get());
        assertEquals(1, outcomes.absentConfirmAttempts.get());
        assertEquals(0, outcomes.confirms.get());
        assertEquals(0, outcomes.manualReviews.get());
        assertEquals(DispatchOutboxStatus.DISPATCHING, outboxes.current().status());
        assertEquals(Optional.of(WorkerCancelPublisher.CANCEL_ABSENCE_UNPROVEN),
                outboxes.current().lastCode());

        WorkerCancelPublisher reconciler = new WorkerCancelPublisher(
                outboxes, workOrders(order), workerRuns(run), actions, worker, outcomes,
                unusedRuntime(), Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC), lease);

        assertEquals(true, reconciler.tickOnce());
        assertEquals(1, statusCalls.get());
        assertEquals(2, cancelCalls.get());
        assertEquals(1, outcomes.confirms.get());
        assertEquals(0, outcomes.manualReviews.get());
        assertEquals(DispatchOutboxStatus.CONFIRMED, outboxes.current().status());
        assertEquals(Optional.of("WORKER_CANCELLED"), outboxes.current().lastCode());
    }

    @Test
    void cooperativeCancelAcknowledgementWaitsForStatusConfirmationWithoutResendingCancel() {
        WorkOrder order = workOrder();
        WorkerRunRecord run = cancelRequestedRun();
        InMemoryRunStore actions = actions(ActionRunStatus.CANCELLED);
        InMemoryOutboxes outboxes = new InMemoryOutboxes(
                pending(WorkerOutboxOperations.CANCEL, new DispatchOutboxId("cancel-running")));
        WorkerStatusObservation cancelled = WorkerStatusObservation.found(new WorkerRunSnapshot(
                TENANT,
                ORDER_ID,
                RUN_ID,
                OPERATION,
                WorkerRunStatus.CANCELLED,
                Optional.empty(),
                Optional.empty(),
                Optional.of("WORKER_CANCELLED")));
        RecordingWorker worker = new RecordingWorker(WorkerRunStatus.CANCEL_REQUESTED, cancelled);
        RecordingOutcomes outcomes = new RecordingOutcomes(outboxes);
        Duration lease = Duration.ofSeconds(1);
        WorkerCancelPublisher submitter = new WorkerCancelPublisher(
                outboxes, workOrders(order), workerRuns(run), actions, worker, outcomes,
                unusedRuntime(), CLOCK, lease);

        assertEquals(true, submitter.tickOnce());
        assertEquals(1, worker.cancelCalls.get());
        assertEquals(0, worker.statusCalls.get());
        assertEquals(0, outcomes.confirms.get());
        assertEquals(0, outcomes.manualReviews.get());
        assertEquals(DispatchOutboxStatus.DISPATCHING, outboxes.current().status());

        Clock afterLease = Clock.fixed(NOW.plusSeconds(4), ZoneOffset.UTC);
        WorkerCancelPublisher reconciler = new WorkerCancelPublisher(
                outboxes, workOrders(order), workerRuns(run), actions, worker, outcomes,
                unusedRuntime(), afterLease, lease);

        assertEquals(true, reconciler.tickOnce());
        assertEquals(1, worker.cancelCalls.get());
        assertEquals(1, worker.statusCalls.get());
        assertEquals(1, outcomes.confirms.get());
        assertEquals(0, outcomes.manualReviews.get());
        assertEquals(DispatchOutboxStatus.CONFIRMED, outboxes.current().status());
        assertEquals(Optional.of(WorkerCancelPublisher.CANCEL_CONFIRMED), outboxes.current().lastCode());
    }

    @Test
    void completionWinningAfterCancelHookProjectsCanonicalWorkerAndSupersedesCancel() {
        WorkOrder order = workOrder();
        WorkerRunRecord run = cancelRequestedRun();
        InMemoryRunStore actions = actions(ActionRunStatus.FAILED);
        InMemoryOutboxes outboxes = new InMemoryOutboxes(
                pending(WorkerOutboxOperations.CANCEL, new DispatchOutboxId("cancel-race")));
        RecordingWorker worker = new RecordingWorker(false);
        RecordingOutcomes outcomes = new RecordingOutcomes(outboxes);
        WorkerCancelPublisher publisher = new WorkerCancelPublisher(
                outboxes, workOrders(order), workerRuns(run), actions, worker, outcomes,
                unusedRuntime(), CLOCK, Duration.ofSeconds(30));

        assertEquals(true, publisher.tickOnce());
        assertEquals(0, worker.cancelCalls.get());
        assertEquals(1, outcomes.superseded.get());
        assertEquals(0, outcomes.manualReviews.get());
        assertEquals(DispatchOutboxStatus.SUPERSEDED, outboxes.current().status());
        assertEquals(
                Optional.of(WorkerCancelPublisher.CANCEL_SUPERSEDED),
                outboxes.current().lastCode());
    }

    @Test
    void alreadyProjectedTerminalCompletionSupersedesCancelAndInvokesNoRemoteEffect() {
        WorkOrder order = workOrder();
        WorkerCompletion failure = canonicalFailure();
        WorkerRunRecord terminal = cancelRequestedRun().complete(
                failure.terminalStatus(),
                failure.resultArtifactManifestRef(),
                failure.resultHash(),
                failure.code(),
                failure.message(),
                failure.retryDisposition(),
                failure.completedAt());
        InMemoryRunStore actions = actions(ActionRunStatus.FAILED);
        InMemoryOutboxes outboxes = new InMemoryOutboxes(
                pending(WorkerOutboxOperations.CANCEL, new DispatchOutboxId("cancel-superseded")));
        RecordingWorker worker = new RecordingWorker(false);
        RecordingOutcomes outcomes = new RecordingOutcomes(outboxes);
        WorkerCancelPublisher publisher = new WorkerCancelPublisher(
                outboxes, workOrders(order), workerRuns(terminal), actions, worker, outcomes,
                unusedRuntime(), CLOCK, Duration.ofSeconds(30));

        assertEquals(true, publisher.tickOnce());
        assertEquals(0, worker.cancelCalls.get());
        assertEquals(1, outcomes.superseded.get());
        assertEquals(DispatchOutboxStatus.SUPERSEDED, outboxes.current().status());
        assertEquals(Optional.of(WorkerCancelPublisher.CANCEL_SUPERSEDED), outboxes.current().lastCode());
    }

    private static WorkerRunRecord cancelRequestedRun() {
        WorkerRunRecord requested = new WorkerRunRecord(
                RUN_ID, TENANT, SESSION, ORDER_ID, 1, "codex-worker", "1",
                new WorkerCapabilities(Set.of()), WorkerRunStatus.REQUESTED, Optional.empty(),
                OPERATION, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                NOW.plusSeconds(300), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW, NOW);
        return requested.startDispatch(
                        DISPATCH_ID,
                        ACTION_ID,
                        WorkerDispatchOperationIds.hashAttemptToken(ATTEMPT_TOKEN),
                        NOW.plusSeconds(1))
                .requestCancellation(NOW.plusSeconds(2));
    }

    private static DispatchRecoveryResult recoverStatusOnlyAfterDeadline(
            Optional<Instant> effectAcceptedAt,
            Optional<Instant> effectTerminalAt) {
        Instant deadline = NOW.plusSeconds(2);
        Instant restartAt = NOW.plusSeconds(3);
        Clock restartClock = Clock.fixed(restartAt, ZoneOffset.UTC);
        WorkOrder order = workOrder(deadline);
        AtomicReference<WorkerRunRecord> runValue = new AtomicReference<>(dispatchingRun(deadline));
        WorkerRunRepository workerRuns = new WorkerRunRepository() {
            @Override public void create(WorkerRunRecord run) { throw new UnsupportedOperationException(); }
            @Override public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId) {
                WorkerRunRecord current = runValue.get();
                return current.tenantId().equals(tenantId) && current.workerRunId().equals(workerRunId)
                        ? Optional.of(current) : Optional.empty();
            }
            @Override public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
                return runValue.compareAndSet(expected, next);
            }
        };
        InMemoryRunStore actions = actions(ActionRunStatus.WAITING_EXTERNAL, deadline);
        InMemoryOutboxes outboxes = new InMemoryOutboxes(
                pending(WorkerOutboxOperations.DISPATCH, DISPATCH_ID)
                        .claimForSubmission("abandoned-status-only", NOW, Duration.ofSeconds(1)));
        CodingWorkerCompletionPayload payload = new CodingWorkerCompletionPayload(
                WorkerProtocol.COMPLETION_SCHEMA_VERSION,
                "event-status-only",
                ORDER_ID,
                RUN_ID,
                OPERATION,
                WorkerAttemptProofs.create(ATTEMPT_TOKEN, "event-status-only", OPERATION, RUN_ID),
                WorkerRunStatus.FAILED,
                Optional.empty(),
                "PROVIDER_FAILURE");
        AtomicInteger statusCalls = new AtomicInteger();
        CodingWorker worker = new CodingWorker() {
            @Override public WorkerCapabilities capabilities() { return new WorkerCapabilities(Set.of()); }
            @Override public WorkerSubmission submit(WorkerDispatchRequest request) {
                throw new AssertionError("expired dispatch recovery must not resubmit");
            }
            @Override public WorkerStatusObservation status(WorkerStatusRequest request) {
                statusCalls.incrementAndGet();
                return WorkerStatusObservation.terminal(
                        new WorkerRunSnapshot(
                                TENANT, ORDER_ID, RUN_ID, OPERATION, WorkerRunStatus.FAILED,
                                effectAcceptedAt, effectTerminalAt,
                                Optional.of("worker-session"), Optional.empty(),
                                Optional.of("WORKER_FAILED")),
                        payload);
            }
            @Override public WorkerCancelResult cancel(WorkerCancelRequest request) {
                throw new AssertionError("dispatch recovery must not cancel");
            }
        };
        AtomicInteger acceptanceCalls = new AtomicInteger();
        WorkerDispatchAcceptanceTransaction acceptance = (
                tenantId, workerRunId, outboxId, operationId, claimToken,
                externalSessionRef, acceptedAt, recordedAt) -> {
            acceptanceCalls.incrementAndGet();
            WorkerRunRecord currentRun = runValue.get();
            DispatchOutbox currentOutbox = outboxes.current();
            WorkerRunRecord waiting = currentRun.awaitExternal(externalSessionRef, acceptedAt);
            DispatchOutbox dispatched = currentOutbox.dispatched(
                    claimToken, WorkerDispatchPublisher.DISPATCH_ACCEPTED, recordedAt);
            return runValue.compareAndSet(currentRun, waiting)
                    && outboxes.compareAndSet(currentOutbox, dispatched);
        };
        AtomicInteger stagedCompletions = new AtomicInteger();
        WorkerDispatchPublisher publisher = new WorkerDispatchPublisher(
                outboxes,
                dispatchStarts(outboxes),
                (observedAt, grace) -> false,
                workOrders(order),
                workerRuns,
                actions,
                worker,
                acceptance,
                (tenantId, bindingId, completion, observedAt) -> {
                    stagedCompletions.incrementAndGet();
                    return new WorkerCallbackReceipt(
                            WorkerCallbackReceipt.Status.ACCEPTED,
                            "WORKER_CALLBACK_ACCEPTED",
                            Optional.of("callback-status-only"));
                },
                emptyInbox(),
                new WorkerDispatchUncertaintyService(
                        workerRuns, actions, completingRuntime(actions, restartAt)),
                restartClock,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10));

        assertEquals(true, publisher.tickOnce());
        return new DispatchRecoveryResult(
                runValue.get(), outboxes.current(), statusCalls.get(), acceptanceCalls.get(),
                stagedCompletions.get());
    }

    private static WorkOrder workOrder() {
        return workOrder(NOW.plusSeconds(300));
    }

    private static WorkOrder workOrder(Instant deadline) {
        return new WorkOrder(
                ORDER_ID, TENANT, SESSION, "GENERATE_CANDIDATE", "Generate candidate", 1,
                Optional.empty(), Optional.empty(), Optional.empty(),
                new ArtifactReference("artifact:instruction"), hash("a"),
                new ArtifactReference("artifact:input"), hash("b"),
                "workspace:cancel", List.of("src"), List.of("src"), Set.of(),
                "factory.candidate.v1", "1", new ArtifactReference("artifact:policy"),
                deadline, 1, "logical-cancel", WorkOrderCreatorType.SYSTEM,
                "test", NOW);
    }

    private static BuildSession runningSession() {
        return new BuildSession(
                SESSION,
                TENANT,
                new ProjectId("project-cancel"),
                ProductLineId.AGENT_PACK,
                "request-cancel",
                "test",
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.GENERATE_CANDIDATE,
                new ArtifactReference("artifact:requirements"),
                hash("c"),
                Optional.empty(),
                Optional.of("codex-worker"),
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

    private static DispatchOutbox pending(String operationType, DispatchOutboxId id) {
        return new DispatchOutbox(
                id, TENANT, operationType, "WORKER_RUN", RUN_ID.value(), OPERATION,
                new ArtifactReference("artifact:input"), DispatchOutboxStatus.PENDING,
                NOW, 0, Optional.empty(), 0, NOW, NOW);
    }

    private static InMemoryRunStore actions(ActionRunStatus status) {
        return actions(status, NOW.plusSeconds(300));
    }

    private static InMemoryRunStore actions(ActionRunStatus status, Instant deadline) {
        InMemoryRunStore store = new InMemoryRunStore();
        ActionExecutionResult result = status == ActionRunStatus.FAILED
                ? WorkerCompletionActionProjection.toActionResult(canonicalFailure())
                : null;
        store.create(ActionRun.builder()
                .runId(ACTION_ID)
                .tenantId(TENANT.value())
                .actionId(WorkerDispatchAction.ACTION_ID)
                .input(Map.of(
                        WorkerDispatchAction.WORK_ORDER_ID, ORDER_ID.value(),
                        WorkerDispatchAction.WORKER_RUN_ID, RUN_ID.value(),
                        WorkerDispatchAction.EXPECTED_WORKER_RUN_VERSION, 0L))
                .status(status)
                .attemptToken(ATTEMPT_TOKEN)
                .externalOperationId(OPERATION)
                .result(result)
                .dueAt(deadline)
                .createdAt(NOW)
                .updatedAt(NOW.plusSeconds(2))
                .build());
        return store;
    }

    private static WorkerRunRecord dispatchingRun(Instant deadline) {
        WorkerRunRecord requested = new WorkerRunRecord(
                RUN_ID, TENANT, SESSION, ORDER_ID, 1, "codex-worker", "1",
                new WorkerCapabilities(Set.of()), WorkerRunStatus.REQUESTED, Optional.empty(),
                OPERATION, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                deadline, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW, NOW);
        return requested.startDispatch(
                DISPATCH_ID,
                ACTION_ID,
                WorkerDispatchOperationIds.hashAttemptToken(ATTEMPT_TOKEN),
                NOW.plusSeconds(1));
    }

    private static WorkerCompletion canonicalFailure() {
        return new WorkerCompletion(
                TENANT,
                RUN_ID,
                OPERATION,
                ATTEMPT_TOKEN,
                WorkerRunStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                "WORKER_FAILED",
                "canonical failure",
                io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition.NEVER,
                NOW.plusMillis(1_500));
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

    private static WorkerRunRepository workerRuns(WorkerRunRecord initial) {
        AtomicReference<WorkerRunRecord> value = new AtomicReference<>(initial);
        return new WorkerRunRepository() {
            @Override public void create(WorkerRunRecord run) { throw new UnsupportedOperationException(); }
            @Override public Optional<WorkerRunRecord> find(TenantId tenantId, WorkerRunId workerRunId) {
                WorkerRunRecord current = value.get();
                return current.tenantId().equals(tenantId) && current.workerRunId().equals(workerRunId)
                        ? Optional.of(current) : Optional.empty();
            }
            @Override public boolean compareAndSet(WorkerRunRecord expected, WorkerRunRecord next) {
                return value.compareAndSet(expected, next);
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

    private static WorkerCallbackInboxRepository emptyInbox() {
        return new WorkerCallbackInboxRepository() {
            @Override public void create(WorkerCallbackInboxEntry entry) {
                throw new UnsupportedOperationException();
            }
            @Override public Optional<WorkerCallbackInboxEntry> find(TenantId tenantId, String callbackId) {
                return Optional.empty();
            }
            @Override public Optional<WorkerCallbackInboxEntry> findByEvent(
                    TenantId tenantId, String workerBindingId, String eventId) {
                return Optional.empty();
            }
            @Override public Optional<WorkerCallbackInboxEntry> claimNext(
                    Instant now, Duration lease, String claimToken) {
                return Optional.empty();
            }
            @Override public Optional<WorkerCallbackInboxEntry> claimExpired(
                    Instant now, Duration lease, String claimToken) {
                return Optional.empty();
            }
            @Override public boolean compareAndSet(
                    WorkerCallbackInboxEntry expected, WorkerCallbackInboxEntry next) {
                return false;
            }
        };
    }

    private static WorkerDispatchStartTransaction dispatchStarts(InMemoryOutboxes outboxes) {
        return (now, lease, token) -> outboxes.claimNextForSubmission(
                WorkerOutboxOperations.DISPATCH, now, lease, token);
    }

    private static CompletableActionRuntime recoveringCancelRuntime(InMemoryRunStore actions) {
        return new CompletableActionRuntime() {
            @Override public ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context) {
                throw new UnsupportedOperationException();
            }
            @Override public ActionExecutionResult resume(String runId, ApprovalDecision decision) {
                throw new UnsupportedOperationException();
            }
            @Override public ActionExecutionResult complete(
                    String runId, String attemptToken, ActionExecutionResult result) {
                throw new UnsupportedOperationException();
            }
            @Override public ActionExecutionResult cancel(String runId, String reason) {
                ActionRun current = actions.find(runId).orElseThrow();
                ActionExecutionResult result = ActionExecutionResult.cancelled(
                        "ACTION_CANCELLED_EXTERNAL_CANCEL_FAILED", reason);
                ActionRun cancelled = current.toBuilder()
                        .version(current.version() + 1)
                        .status(ActionRunStatus.CANCELLED)
                        .result(result)
                        .updatedAt(CLOCK.instant())
                        .build();
                if (!actions.compareAndSet(current, cancelled)) {
                    throw new IllegalStateException("simulated Action cancellation CAS lost");
                }
                return result;
            }
        };
    }

    private static CompletableActionRuntime completingRuntime(
            InMemoryRunStore actions, Instant completedAt) {
        return new CompletableActionRuntime() {
            @Override public ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context) {
                throw new UnsupportedOperationException();
            }
            @Override public ActionExecutionResult resume(String runId, ApprovalDecision decision) {
                throw new UnsupportedOperationException();
            }
            @Override public ActionExecutionResult complete(
                    String runId, String attemptToken, ActionExecutionResult result) {
                ActionRun current = actions.find(runId).orElseThrow();
                assertEquals(current.attemptToken(), attemptToken);
                ActionRunStatus status = result.terminalSuccess()
                        ? ActionRunStatus.SUCCEEDED
                        : ActionRunStatus.FAILED;
                ActionRun terminal = current.toBuilder()
                        .version(current.version() + 1)
                        .status(status)
                        .result(result)
                        .updatedAt(completedAt)
                        .build();
                if (!actions.compareAndSet(current, terminal)) {
                    throw new IllegalStateException("simulated Action completion CAS lost");
                }
                return result;
            }
            @Override public ActionExecutionResult cancel(String runId, String reason) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ContentHash hash(String seed) {
        return new ContentHash(seed.repeat(64).substring(0, 64));
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class RecordingWorker implements CodingWorker {
        private final boolean cancelNotFound;
        private final boolean statusNotFound;
        private final WorkerRunStatus cancelStatus;
        private final WorkerStatusObservation statusObservation;
        private final AtomicInteger submitCalls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicInteger statusCalls = new AtomicInteger();

        private RecordingWorker(boolean cancelNotFound) { this(cancelNotFound, false); }
        private RecordingWorker(boolean cancelNotFound, boolean statusNotFound) {
            this.cancelNotFound = cancelNotFound;
            this.statusNotFound = statusNotFound;
            this.cancelStatus = WorkerRunStatus.CANCELLED;
            this.statusObservation = null;
        }
        private RecordingWorker(
                WorkerRunStatus cancelStatus, WorkerStatusObservation statusObservation) {
            this.cancelNotFound = false;
            this.statusNotFound = false;
            this.cancelStatus = cancelStatus;
            this.statusObservation = statusObservation;
        }
        @Override public WorkerCapabilities capabilities() { return new WorkerCapabilities(Set.of()); }
        @Override public WorkerSubmission submit(WorkerDispatchRequest request) {
            submitCalls.incrementAndGet();
            throw new AssertionError("submit must not be called");
        }
        @Override public WorkerStatusObservation status(WorkerStatusRequest request) {
            statusCalls.incrementAndGet();
            if (statusObservation != null) return statusObservation;
            if (statusNotFound) return WorkerStatusObservation.notFound();
            throw new AssertionError("status must not be called for this claim");
        }
        @Override public WorkerCancelResult cancel(WorkerCancelRequest request) {
            cancelCalls.incrementAndGet();
            if (cancelNotFound) throw new WorkerOperationNotFoundException();
            return new WorkerCancelResult(RUN_ID, cancelStatus, "WORKER_CANCELLED");
        }
    }

    private record DispatchRecoveryResult(
            WorkerRunRecord workerRun,
            DispatchOutbox outbox,
            int statusCalls,
            int acceptanceCalls,
            int stagedCompletions) {}

    private static final class RecordingOutcomes implements WorkerCancellationOutcomeTransaction {
        private final InMemoryOutboxes outboxes;
        private final boolean noDispatchProven;
        private final AtomicInteger confirms = new AtomicInteger();
        private final AtomicInteger absentConfirmAttempts = new AtomicInteger();
        private final AtomicInteger manualReviews = new AtomicInteger();
        private final AtomicInteger superseded = new AtomicInteger();

        private RecordingOutcomes(InMemoryOutboxes outboxes) { this(outboxes, true); }
        private RecordingOutcomes(InMemoryOutboxes outboxes, boolean noDispatchProven) {
            this.outboxes = outboxes;
            this.noDispatchProven = noDispatchProven;
        }

        @Override
        public boolean confirm(
                TenantId tenantId, WorkerRunId workerRunId, DispatchOutboxId outboxId,
                String operationId, String claimToken, String stableCode, Instant confirmedAt) {
            confirms.incrementAndGet();
            DispatchOutbox current = outboxes.current();
            return outboxes.compareAndSet(
                    current, current.confirmed(claimToken, stableCode, confirmedAt));
        }

        @Override
        public boolean confirmAbsentAfterProvenNoDispatch(
                TenantId tenantId, WorkerRunId workerRunId, DispatchOutboxId cancelOutboxId,
                DispatchOutboxId dispatchOutboxId, String operationId, String claimToken,
                String stableCode, Instant confirmedAt) {
            absentConfirmAttempts.incrementAndGet();
            return noDispatchProven && confirm(
                    tenantId, workerRunId, cancelOutboxId, operationId, claimToken,
                    stableCode, confirmedAt);
        }

        @Override
        public boolean manualReview(
                TenantId tenantId, WorkerRunId workerRunId, DispatchOutboxId outboxId,
                String operationId, String claimToken, String stableCode, Instant observedAt) {
            manualReviews.incrementAndGet();
            DispatchOutbox current = outboxes.current();
            return outboxes.compareAndSet(
                    current, current.manualReview(claimToken, stableCode, observedAt));
        }

        @Override
        public boolean supersedeAfterTerminalCompletion(
                TenantId tenantId, WorkerRunId workerRunId, DispatchOutboxId outboxId,
                String operationId, String claimToken, String stableCode, Instant observedAt) {
            superseded.incrementAndGet();
            DispatchOutbox current = outboxes.current();
            return outboxes.compareAndSet(
                    current, current.superseded(claimToken, stableCode, observedAt));
        }
    }

    private static final class InMemoryOutboxes implements DispatchOutboxRepository {
        private final AtomicReference<DispatchOutbox> value;

        private InMemoryOutboxes(DispatchOutbox initial) { value = new AtomicReference<>(initial); }
        DispatchOutbox current() { return value.get(); }
        @Override public void create(DispatchOutbox outbox) { throw new UnsupportedOperationException(); }
        @Override public Optional<DispatchOutbox> find(TenantId tenantId, DispatchOutboxId outboxId) {
            DispatchOutbox current = value.get();
            return current.tenantId().equals(tenantId) && current.outboxId().equals(outboxId)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<DispatchOutbox> claimNextForSubmission(
                String operationType, Instant now, Duration lease, String claimToken) {
            DispatchOutbox current = value.get();
            if (!current.operationType().equals(operationType)
                    || (current.status() != DispatchOutboxStatus.PENDING
                            && current.status() != DispatchOutboxStatus.RETRY_WAIT)) {
                return Optional.empty();
            }
            DispatchOutbox claimed = current.claimForSubmission(claimToken, now, lease);
            return value.compareAndSet(current, claimed) ? Optional.of(claimed) : Optional.empty();
        }
        @Override public Optional<DispatchOutbox> claimExpiredForReconciliation(
                String operationType, Instant now, Duration lease, String claimToken) {
            DispatchOutbox current = value.get();
            if (!current.operationType().equals(operationType)
                    || current.status() != DispatchOutboxStatus.DISPATCHING
                    || current.leaseUntil().filter(value -> !now.isBefore(value)).isEmpty()) {
                return Optional.empty();
            }
            DispatchOutbox claimed = current.claimForReconciliation(claimToken, now, lease);
            return value.compareAndSet(current, claimed) ? Optional.of(claimed) : Optional.empty();
        }
        @Override public boolean compareAndSet(DispatchOutbox expected, DispatchOutbox next) {
            return value.compareAndSet(expected, next);
        }
    }

    private static final class SingleInbox implements WorkerCallbackInboxRepository {
        private final AtomicReference<WorkerCallbackInboxEntry> value;

        private SingleInbox(WorkerCallbackInboxEntry initial) { value = new AtomicReference<>(initial); }
        WorkerCallbackInboxEntry current() { return value.get(); }
        @Override public void create(WorkerCallbackInboxEntry entry) { throw new UnsupportedOperationException(); }
        @Override public Optional<WorkerCallbackInboxEntry> find(TenantId tenantId, String callbackId) {
            WorkerCallbackInboxEntry current = value.get();
            return current.tenantId().equals(tenantId) && current.callbackId().equals(callbackId)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<WorkerCallbackInboxEntry> findByEvent(
                TenantId tenantId, String workerBindingId, String eventId) {
            WorkerCallbackInboxEntry current = value.get();
            return current.tenantId().equals(tenantId)
                            && current.workerBindingId().equals(workerBindingId)
                            && current.eventId().equals(eventId)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<WorkerCallbackInboxEntry> claimNext(
                Instant now, Duration lease, String claimToken) { return Optional.empty(); }
        @Override public Optional<WorkerCallbackInboxEntry> claimExpired(
                Instant now, Duration lease, String claimToken) { return Optional.empty(); }
        @Override public boolean compareAndSet(
                WorkerCallbackInboxEntry expected, WorkerCallbackInboxEntry next) {
            return value.compareAndSet(expected, next);
        }
    }

    private static final class StagedInbox implements WorkerCallbackInboxRepository {
        private final AtomicReference<WorkerCallbackInboxEntry> value = new AtomicReference<>();

        WorkerCallbackInboxEntry current() { return value.get(); }
        @Override public void create(WorkerCallbackInboxEntry entry) {
            if (!value.compareAndSet(null, entry)) {
                throw new IllegalStateException("duplicate staged callback");
            }
        }
        @Override public Optional<WorkerCallbackInboxEntry> find(
                TenantId tenantId, String callbackId) {
            WorkerCallbackInboxEntry current = value.get();
            return current != null
                            && current.tenantId().equals(tenantId)
                            && current.callbackId().equals(callbackId)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<WorkerCallbackInboxEntry> findByEvent(
                TenantId tenantId, String workerBindingId, String eventId) {
            WorkerCallbackInboxEntry current = value.get();
            return current != null
                            && current.tenantId().equals(tenantId)
                            && current.workerBindingId().equals(workerBindingId)
                            && current.eventId().equals(eventId)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<WorkerCallbackInboxEntry> claimNext(
                Instant now, Duration lease, String claimToken) {
            WorkerCallbackInboxEntry current = value.get();
            if (current == null || current.status() != WorkerCallbackInboxStatus.RECEIVED
                    || now.isBefore(current.availableAt())) {
                return Optional.empty();
            }
            WorkerCallbackInboxEntry claimed = current.claim(claimToken, now, lease);
            return value.compareAndSet(current, claimed) ? Optional.of(claimed) : Optional.empty();
        }
        @Override public Optional<WorkerCallbackInboxEntry> claimExpired(
                Instant now, Duration lease, String claimToken) {
            return Optional.empty();
        }
        @Override public boolean compareAndSet(
                WorkerCallbackInboxEntry expected, WorkerCallbackInboxEntry next) {
            return value.compareAndSet(expected, next);
        }
    }
}
