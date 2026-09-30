package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchInput;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.outbox.DispatchClaimPurpose;
import io.github.flowerjvm.factory.application.outbox.DispatchOutbox;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxRepository;
import io.github.flowerjvm.factory.application.outbox.DispatchOutboxStatus;
import io.github.flowerjvm.factory.application.outbox.WorkerOutboxOperations;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.CodingWorker;
import io.github.flowerjvm.factory.contracts.worker.WorkerAttemptToken;
import io.github.flowerjvm.factory.contracts.worker.WorkerDispatchException;
import io.github.flowerjvm.factory.contracts.worker.WorkerDispatchRequest;
import io.github.flowerjvm.factory.contracts.worker.WorkerEffectCertainty;
import io.github.flowerjvm.factory.contracts.worker.WorkerLookupState;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunSnapshot;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.factory.contracts.worker.WorkerStatusObservation;
import io.github.flowerjvm.factory.contracts.worker.WorkerStatusRequest;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded outbox publisher and status reconciler for the actual Coding Worker adapter. */
public final class WorkerDispatchPublisher {
    public static final String DISPATCH_ACCEPTED = "WORKER_DISPATCH_ACCEPTED";
    public static final String OWNER_INVALID = "WORKER_DISPATCH_OWNER_INVALID";
    public static final String ADAPTER_CAPABILITY_DRIFT = "WORKER_ADAPTER_CAPABILITY_DRIFT";
    public static final String ADAPTER_CAPABILITY_LOOKUP_FAILED =
            "WORKER_ADAPTER_CAPABILITY_LOOKUP_FAILED";
    public static final String ADAPTER_RESPONSE_INVALID = "WORKER_ADAPTER_RESPONSE_INVALID";
    public static final String STATUS_UNKNOWN = "WORKER_STATUS_UNKNOWN";
    public static final String ACCEPTED_OPERATION_MISSING = "WORKER_ACCEPTED_OPERATION_MISSING";
    public static final String CANCELLED_BEFORE_SUBMIT = "WORKER_DISPATCH_CANCELLED_BEFORE_SUBMIT";
    public static final String START_BOUNDARY_INVALID = "WORKER_DISPATCH_START_BOUNDARY_INVALID";
    public static final String ACCEPTANCE_TIME_UNPROVEN = "WORKER_ACCEPTANCE_TIME_UNPROVEN";
    public static final String TERMINAL_TIME_UNPROVEN = "WORKER_TERMINAL_TIME_UNPROVEN";
    public static final String DISPATCH_DEADLINE_EXCEEDED = "WORKER_DISPATCH_DEADLINE_EXCEEDED";
    public static final String ACTION_PARK_ORPHANED = "WORKER_DISPATCH_ACTION_PARK_ORPHANED";
    public static final String ACTION_PARK_CANCEL_ORPHANED =
            "WORKER_DISPATCH_ACTION_PARK_CANCEL_ORPHANED";
    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);
    public static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(30);
    public static final Duration DEFAULT_STATUS_INTERVAL = Duration.ofSeconds(30);
    public static final Duration DEFAULT_PRE_PARK_GRACE = Duration.ofSeconds(30);

    private final DispatchOutboxRepository outboxes;
    private final WorkerDispatchStartTransaction dispatchStarts;
    private final WorkerDispatchPreParkOrphanTransaction preParkOrphans;
    private final WorkOrderRepository workOrders;
    private final WorkerRunRepository workerRuns;
    private final RunStore runStore;
    private final CodingWorker worker;
    private final WorkerDispatchAcceptanceTransaction acceptance;
    private final WorkerCompletionPayloadStager completionStager;
    private final WorkerCallbackInboxRepository callbackInbox;
    private final WorkerDispatchUncertaintyService uncertainty;
    private final Clock clock;
    private final Duration lease;
    private final Duration retryBackoff;
    private final Duration statusInterval;

    public WorkerDispatchPublisher(
            DispatchOutboxRepository outboxes,
            WorkerDispatchStartTransaction dispatchStarts,
            WorkerDispatchPreParkOrphanTransaction preParkOrphans,
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            RunStore runStore,
            CodingWorker worker,
            WorkerDispatchAcceptanceTransaction acceptance,
            WorkerCompletionPayloadStager completionStager,
            WorkerCallbackInboxRepository callbackInbox,
            WorkerDispatchUncertaintyService uncertainty,
            Clock clock) {
        this(
                outboxes, dispatchStarts, preParkOrphans, workOrders, workerRuns, runStore, worker,
                acceptance, completionStager, callbackInbox, uncertainty, clock, DEFAULT_LEASE,
                DEFAULT_RETRY_BACKOFF, DEFAULT_STATUS_INTERVAL);
    }

    public WorkerDispatchPublisher(
            DispatchOutboxRepository outboxes,
            WorkerDispatchStartTransaction dispatchStarts,
            WorkerDispatchPreParkOrphanTransaction preParkOrphans,
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            RunStore runStore,
            CodingWorker worker,
            WorkerDispatchAcceptanceTransaction acceptance,
            WorkerCompletionPayloadStager completionStager,
            WorkerCallbackInboxRepository callbackInbox,
            WorkerDispatchUncertaintyService uncertainty,
            Clock clock,
            Duration lease,
            Duration retryBackoff,
            Duration statusInterval) {
        this.outboxes = Objects.requireNonNull(outboxes, "outboxes");
        this.dispatchStarts = Objects.requireNonNull(dispatchStarts, "dispatchStarts");
        this.preParkOrphans = Objects.requireNonNull(preParkOrphans, "preParkOrphans");
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
        this.workerRuns = Objects.requireNonNull(workerRuns, "workerRuns");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.acceptance = Objects.requireNonNull(acceptance, "acceptance");
        this.completionStager = Objects.requireNonNull(completionStager, "completionStager");
        this.callbackInbox = Objects.requireNonNull(callbackInbox, "callbackInbox");
        this.uncertainty = Objects.requireNonNull(uncertainty, "uncertainty");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lease = positive(lease, "lease");
        this.retryBackoff = positive(retryBackoff, "retryBackoff");
        this.statusInterval = positive(statusInterval, "statusInterval");
    }

    /** Claims and handles at most one outbox row. */
    public boolean tickOnce() {
        Instant now = clock.instant();
        String claimToken = UUID.randomUUID().toString();
        var claimed = outboxes.claimExpiredForReconciliation(
                WorkerOutboxOperations.DISPATCH, now, lease, claimToken);
        if (claimed.isEmpty()) {
            if (preParkOrphans.reconcileNext(now, DEFAULT_PRE_PARK_GRACE)) {
                return true;
            }
            claimToken = UUID.randomUUID().toString();
            claimed = dispatchStarts.claimNext(now, lease, claimToken);
        }
        if (claimed.isEmpty()) {
            return false;
        }
        if (claimed.orElseThrow().status() != DispatchOutboxStatus.DISPATCHING) {
            handleRejectedStart(claimed.orElseThrow(), now);
            return true;
        }
        process(claimed.orElseThrow(), claimToken, now);
        return true;
    }

    private void handleRejectedStart(DispatchOutbox outbox, Instant observedAt) {
        String code = outbox.lastCode().orElse(START_BOUNDARY_INVALID);
        if (CANCELLED_BEFORE_SUBMIT.equals(code)) {
            // The cancellation path owns Action first-terminalization and durable cancel intent.
            return;
        }
        WorkerRunRecord run = findAggregate(outbox);
        if (run != null) {
            uncertainty.manualReview(
                    run.tenantId(), run.workerRunId(), run.operationId(), code, observedAt);
        }
    }

    /** Status-polls accepted WAITING_EXTERNAL rows without requiring a provider session resume. */
    public int pollAccepted(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be positive");
        }
        Instant now = clock.instant();
        List<WorkerRunRecord> active = workerRuns.findWaitingExternalForStatusPoll(
                now.minus(statusInterval), maximum);
        int polled = 0;
        for (WorkerRunRecord run : active) {
            if (polled >= maximum) {
                break;
            }
            if (run.status() == WorkerRunStatus.WAITING_EXTERNAL) {
                pollAccepted(run, now);
                polled++;
            }
        }
        return polled;
    }

    private void process(DispatchOutbox outbox, String claimToken, Instant now) {
        if (!WorkerOutboxOperations.DISPATCH.equals(outbox.operationType())
                || outbox.claimToken().filter(claimToken::equals).isEmpty()) {
            return;
        }
        WorkerRunRecord run = findAggregate(outbox);
        var order = run == null ? null : workOrders.find(outbox.tenantId(), run.workOrderId()).orElse(null);
        ActionRun action = run == null ? null : run.actionRunId().flatMap(runStore::find).orElse(null);
        boolean cancelledOwner = action != null
                && action.status() == ActionRunStatus.CANCELLED
                && exactOwnerBinding(outbox, run, order, action);
        if (cancelledOwner
                && outbox.claimPurpose().orElseThrow() == DispatchClaimPurpose.SUBMIT) {
            // The Action cancellation won before the dispatch publisher called the adapter. The
            // separate WORKER_CANCEL outbox records remote absence/confirmation; this dispatch
            // intent must only stop and must never claim that a submit was accepted.
            outboxes.compareAndSet(
                    outbox, outbox.manualReview(claimToken, CANCELLED_BEFORE_SUBMIT, now));
            return;
        }
        if (!exactOwner(outbox, run, order, action)
                && !(cancelledOwner
                        && outbox.claimPurpose().orElseThrow() == DispatchClaimPurpose.RECONCILE)) {
            terminalizeUnknown(outbox, claimToken, run, OWNER_INVALID, now);
            return;
        }
        if (outbox.claimPurpose().orElseThrow() == DispatchClaimPurpose.SUBMIT) {
            submit(outbox, claimToken, run, order, action, now);
        } else {
            reconcile(outbox, claimToken, run, order, action, now);
        }
    }

    private void submit(
            DispatchOutbox outbox,
            String claimToken,
            WorkerRunRecord run,
            io.github.flowerjvm.factory.contracts.worker.WorkOrder order,
            ActionRun action,
            Instant now) {
        if (!now.isBefore(run.deadlineAt())) {
            terminalizeUnknown(outbox, claimToken, run, DISPATCH_DEADLINE_EXCEEDED, now);
            return;
        }
        boolean submitInvocationStarted = false;
        try {
            if (!worker.capabilities().supportsAll(order.requiredCapabilities())) {
                terminalizeUnknown(outbox, claimToken, run, ADAPTER_CAPABILITY_DRIFT, now);
                return;
            }
            Instant startAt = clock.instant();
            if (!startAt.isBefore(order.deadlineAt())
                    || !startAt.isBefore(run.deadlineAt())) {
                terminalizeUnknown(outbox, claimToken, run, DISPATCH_DEADLINE_EXCEEDED, startAt);
                return;
            }
            var request = new WorkerDispatchRequest(
                    order, run.workerRunId(), run.operationId(), new WorkerAttemptToken(action.attemptToken()));
            submitInvocationStarted = true;
            var submission = worker.submit(request);
            if (!submission.workerRunId().equals(run.workerRunId())
                    || !submission.operationId().equals(run.operationId())
                    || submission.status() != WorkerRunStatus.WAITING_EXTERNAL) {
                terminalizeUnknown(outbox, claimToken, run, ADAPTER_RESPONSE_INVALID, clock.instant());
                return;
            }
            acceptance.accept(
                    outbox.tenantId(), run.workerRunId(), outbox.outboxId(), run.operationId(),
                    claimToken, submission.externalSessionRef().orElse(run.operationId()),
                    clock.instant(), clock.instant());
        } catch (WorkerDispatchException known) {
            boolean provenNoSubmit = !submitInvocationStarted
                    || known.effectCertainty() == WorkerEffectCertainty.NO_EFFECT;
            if (provenNoSubmit
                    && known.retryDisposition() == WorkerRetryDisposition.AFTER_BACKOFF) {
                retryNoEffect(outbox, claimToken, known.stableCode(), clock.instant());
            }
            // UNCERTAIN keeps its lease-bearing DISPATCHING row for status reconciliation.
            else if (provenNoSubmit) {
                terminalizeUnknown(outbox, claimToken, run, known.stableCode(), clock.instant());
            }
        } catch (RuntimeException unexpected) {
            if (!submitInvocationStarted) {
                // Capability/credential isolation is evaluated before the external-effect call.
                // Its implementation details are untrusted, but failure here is authoritatively
                // no-effect and must not strand a DISPATCHING lease in status reconciliation.
                terminalizeUnknown(
                        outbox, claimToken, run, ADAPTER_CAPABILITY_LOOKUP_FAILED, clock.instant());
            }
        }
        // Unknown submit exceptions leave DISPATCHING unchanged; the expired claim is status-only
        // recovery because the adapter effect may already have begun.
    }

    /**
     * Compatibility path lacks the atomic BuildSession start-boundary check. Production hosts must
     * wire {@link WorkerDispatchStartTransaction} through the constructor above.
     */
    @Deprecated(forRemoval = true)
    public WorkerDispatchPublisher(
            DispatchOutboxRepository outboxes,
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            RunStore runStore,
            CodingWorker worker,
            WorkerDispatchAcceptanceTransaction acceptance,
            WorkerCompletionPayloadStager completionStager,
            WorkerCallbackInboxRepository callbackInbox,
            WorkerDispatchUncertaintyService uncertainty,
            Clock clock) {
        this(
                outboxes,
                (now, lease, token) -> outboxes.claimNextForSubmission(
                        WorkerOutboxOperations.DISPATCH, now, lease, token),
                noPreParkRecovery(),
                workOrders,
                workerRuns,
                runStore,
                worker,
                acceptance,
                completionStager,
                callbackInbox,
                uncertainty,
                clock);
    }

    /** @see #WorkerDispatchPublisher(DispatchOutboxRepository, WorkOrderRepository, WorkerRunRepository, RunStore, CodingWorker, WorkerDispatchAcceptanceTransaction, WorkerCompletionPayloadStager, WorkerCallbackInboxRepository, WorkerDispatchUncertaintyService, Clock) */
    @Deprecated(forRemoval = true)
    public WorkerDispatchPublisher(
            DispatchOutboxRepository outboxes,
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            RunStore runStore,
            CodingWorker worker,
            WorkerDispatchAcceptanceTransaction acceptance,
            WorkerCompletionPayloadStager completionStager,
            WorkerCallbackInboxRepository callbackInbox,
            WorkerDispatchUncertaintyService uncertainty,
            Clock clock,
            Duration lease,
            Duration retryBackoff,
            Duration statusInterval) {
        this(
                outboxes,
                (now, duration, token) -> outboxes.claimNextForSubmission(
                        WorkerOutboxOperations.DISPATCH, now, duration, token),
                noPreParkRecovery(),
                workOrders,
                workerRuns,
                runStore,
                worker,
                acceptance,
                completionStager,
                callbackInbox,
                uncertainty,
                clock,
                lease,
                retryBackoff,
                statusInterval);
    }

    private void reconcile(
            DispatchOutbox outbox,
            String claimToken,
            WorkerRunRecord run,
            io.github.flowerjvm.factory.contracts.worker.WorkOrder order,
            ActionRun action,
            Instant now) {
        WorkerStatusObservation observation;
        try {
            observation = worker.status(statusRequest(run, action));
        } catch (RuntimeException unavailable) {
            return;
        }
        switch (observation.lookupState()) {
            case NOT_FOUND -> retryNoEffect(
                    outbox, claimToken, "WORKER_OPERATION_AUTHORITATIVELY_ABSENT", now);
            case UNAVAILABLE -> {
                // Keep DISPATCHING; a later expired reconciliation claim may query again.
            }
            case UNKNOWN -> terminalizeUnknown(
                    outbox, claimToken, run,
                    observation.stableCode().orElse(STATUS_UNKNOWN), now);
            case FOUND -> {
                WorkerRunSnapshot snapshot = observation.snapshot().orElseThrow();
                if (!exactSnapshot(run, snapshot)) {
                    terminalizeUnknown(outbox, claimToken, run, ADAPTER_RESPONSE_INVALID, now);
                    return;
                }
                Optional<WorkerCallbackInboxEntry> trustedReceipt = observation.terminalPayload()
                        .flatMap(payload -> trustedReceipt(run, payload));
                Optional<Instant> durableAuthority = earliestSafeAcceptanceAuthority(
                        run, snapshot, trustedReceipt, now);
                boolean durableEvidencePresent = snapshot.effectAcceptedAt().isPresent()
                        || trustedReceipt.isPresent();
                Instant acceptedAt = durableAuthority.orElseGet(() -> now);
                if (durableAuthority.isEmpty()
                        && (durableEvidencePresent
                                || !now.isBefore(run.deadlineAt())
                                || now.isBefore(run.updatedAt()))) {
                    terminalizeUnknown(
                            outbox, claimToken, run, ACCEPTANCE_TIME_UNPROVEN, now);
                    return;
                }
                CompletionAuthority completionAuthority = completionAuthority(
                        run, snapshot, trustedReceipt, now);
                if (observation.terminalPayload().isPresent()
                        && completionAuthority.receivedAt().isEmpty()) {
                    terminalizeUnknown(
                            outbox, claimToken, run, TERMINAL_TIME_UNPROVEN, now);
                    return;
                }
                boolean accepted = acceptance.accept(
                        outbox.tenantId(), run.workerRunId(), outbox.outboxId(), run.operationId(),
                        claimToken, snapshot.externalSessionRef().orElse(run.operationId()),
                        acceptedAt, now);
                WorkerRunRecord canonical = workerRuns.find(run.tenantId(), run.workerRunId()).orElse(null);
                if ((accepted || (canonical != null
                                && (canonical.status() == WorkerRunStatus.WAITING_EXTERNAL
                                        || canonical.status() == WorkerRunStatus.CANCEL_REQUESTED)))
                        && observation.terminalPayload().isPresent()) {
                    stageOrRecoverExisting(
                            run,
                            observation.terminalPayload().orElseThrow(),
                            completionAuthority.receivedAt().orElseThrow(),
                            now);
                } else if (snapshot.status().isTerminal() && observation.terminalPayload().isEmpty()) {
                    uncertainty.manualReview(
                            run.tenantId(), run.workerRunId(), run.operationId(),
                            ADAPTER_RESPONSE_INVALID, now);
                }
            }
        }
    }

    private static Optional<Instant> earliestSafeAcceptanceAuthority(
            WorkerRunRecord run,
            WorkerRunSnapshot snapshot,
            Optional<WorkerCallbackInboxEntry> trustedReceipt,
            Instant observedAt) {
        return java.util.stream.Stream.concat(
                        snapshot.effectAcceptedAt().stream(),
                        trustedReceipt.stream().map(WorkerCallbackInboxEntry::receivedAt))
                .filter(value -> !value.isBefore(run.updatedAt()))
                .filter(value -> value.isBefore(run.deadlineAt()))
                .filter(value -> !value.isAfter(observedAt))
                .min(Instant::compareTo);
    }

    private void pollAccepted(WorkerRunRecord run, Instant now) {
        ActionRun action = run.actionRunId().flatMap(runStore::find).orElse(null);
        if (action == null || !exactAction(run, action)) {
            uncertainty.manualReview(
                    run.tenantId(), run.workerRunId(), run.operationId(), OWNER_INVALID, now);
            return;
        }
        WorkerStatusObservation observation;
        try {
            observation = worker.status(statusRequest(run, action));
        } catch (RuntimeException unavailable) {
            return;
        }
        switch (observation.lookupState()) {
            case FOUND -> {
                WorkerRunSnapshot snapshot = observation.snapshot().orElseThrow();
                if (!exactSnapshot(run, snapshot)) {
                    uncertainty.manualReview(
                            run.tenantId(), run.workerRunId(), run.operationId(),
                            ADAPTER_RESPONSE_INVALID, now);
                } else if (observation.terminalPayload().isPresent()) {
                    Optional<WorkerCallbackInboxEntry> trustedReceipt = trustedReceipt(
                            run, observation.terminalPayload().orElseThrow());
                    CompletionAuthority authority = completionAuthority(
                            run, snapshot, trustedReceipt, now);
                    if (authority.receivedAt().isEmpty()) {
                        uncertainty.manualReview(
                                run.tenantId(), run.workerRunId(), run.operationId(),
                                TERMINAL_TIME_UNPROVEN, now);
                    } else {
                        stageOrRecoverExisting(
                                run,
                                observation.terminalPayload().orElseThrow(),
                                authority.receivedAt().orElseThrow(),
                                now);
                    }
                } else if (snapshot.status().isTerminal()) {
                    uncertainty.manualReview(
                            run.tenantId(), run.workerRunId(), run.operationId(),
                            ADAPTER_RESPONSE_INVALID, now);
                } else {
                    workerRuns.compareAndSet(run, run.observeHeartbeat(now));
                }
            }
            case NOT_FOUND -> uncertainty.manualReview(
                    run.tenantId(), run.workerRunId(), run.operationId(), ACCEPTED_OPERATION_MISSING, now);
            case UNKNOWN -> uncertainty.manualReview(
                    run.tenantId(), run.workerRunId(), run.operationId(),
                    observation.stableCode().orElse(STATUS_UNKNOWN), now);
            case UNAVAILABLE -> {
                // No mutation: an outage is not evidence that the accepted operation disappeared.
            }
        }
    }

    private Optional<WorkerCallbackInboxEntry> trustedReceipt(
            WorkerRunRecord run,
            io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload payload) {
        return callbackInbox.findByEvent(
                        run.tenantId(), run.workerBindingId(), payload.eventId())
                .filter(entry -> entry.tenantId().equals(run.tenantId()))
                .filter(entry -> entry.workerBindingId().equals(run.workerBindingId()))
                .filter(entry -> entry.workOrderId().equals(run.workOrderId()))
                .filter(entry -> entry.workerRunId().equals(run.workerRunId()))
                .filter(entry -> entry.operationId().equals(run.operationId()))
                .filter(entry -> entry.attemptTokenHash().equals(run.attemptTokenHash().orElse(null)))
                .filter(entry -> entry.eventId().equals(payload.eventId()));
    }

    private void stageOrRecoverExisting(
            WorkerRunRecord run,
            io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload payload,
            Instant trustedReceivedAt,
            Instant now) {
        Optional<WorkerCallbackInboxEntry> existing = trustedReceipt(run, payload);
        if (existing.isEmpty()) {
            completionStager.stage(
                    run.tenantId(), run.workerBindingId(), payload, trustedReceivedAt);
            return;
        }
        WorkerCallbackInboxEntry entry = existing.orElseThrow();
        if (entry.status() == WorkerCallbackInboxStatus.MANUAL_REVIEW
                && entry.lastCode().filter(WorkerCallbackProcessor.ACCEPTANCE_ORPHANED::equals).isPresent()) {
            callbackInbox.compareAndSet(entry, entry.recoverAcceptedDispatch(now));
        }
    }

    private static CompletionAuthority completionAuthority(
            WorkerRunRecord run,
            WorkerRunSnapshot snapshot,
            Optional<WorkerCallbackInboxEntry> trustedReceipt,
            Instant observedAt) {
        Optional<Instant> journalTime = snapshot.effectTerminalAt();
        if (journalTime.isPresent()
                && !safeTerminalTime(run, snapshot, journalTime.orElseThrow(), observedAt)) {
            return new CompletionAuthority(Optional.empty());
        }
        if (trustedReceipt.isPresent()) {
            Instant receivedAt = trustedReceipt.orElseThrow().receivedAt();
            if (!safeReceiptTime(run, receivedAt, observedAt)) {
                return new CompletionAuthority(Optional.empty());
            }
            return new CompletionAuthority(Optional.of(receivedAt));
        }
        if (journalTime.isPresent()) {
            return new CompletionAuthority(journalTime);
        }
        if (safeReceiptTime(run, observedAt, observedAt)) {
            return new CompletionAuthority(Optional.of(observedAt));
        }
        return new CompletionAuthority(Optional.empty());
    }

    private static boolean safeTerminalTime(
            WorkerRunRecord run,
            WorkerRunSnapshot snapshot,
            Instant terminalAt,
            Instant observedAt) {
        return safeReceiptTime(run, terminalAt, observedAt)
                && snapshot.effectAcceptedAt()
                        .filter(acceptedAt -> terminalAt.isBefore(acceptedAt))
                        .isEmpty();
    }

    private static boolean safeReceiptTime(
            WorkerRunRecord run, Instant receivedAt, Instant observedAt) {
        return run.startedAt().filter(startedAt -> !receivedAt.isBefore(startedAt)).isPresent()
                && receivedAt.isBefore(run.deadlineAt())
                && !receivedAt.isAfter(observedAt);
    }

    private record CompletionAuthority(Optional<Instant> receivedAt) {
        private CompletionAuthority {
            Objects.requireNonNull(receivedAt, "receivedAt");
        }
    }

    private void retryNoEffect(DispatchOutbox outbox, String claimToken, String code, Instant now) {
        outboxes.compareAndSet(
                outbox,
                outbox.retryAfterProvenNoEffect(claimToken, stableCode(code), now, now.plus(retryBackoff)));
    }

    private void terminalizeUnknown(
            DispatchOutbox outbox,
            String claimToken,
            WorkerRunRecord run,
            String code,
            Instant now) {
        String stable = stableCode(code);
        if (run != null) {
            uncertainty.manualReview(run.tenantId(), run.workerRunId(), run.operationId(), stable, now);
        }
        outboxes.compareAndSet(outbox, outbox.manualReview(claimToken, stable, now));
    }

    private WorkerRunRecord findAggregate(DispatchOutbox outbox) {
        try {
            return workerRuns.find(outbox.tenantId(), new WorkerRunId(outbox.aggregateId())).orElse(null);
        } catch (RuntimeException invalidAggregate) {
            return null;
        }
    }

    private static boolean exactOwner(
            DispatchOutbox outbox,
            WorkerRunRecord run,
            io.github.flowerjvm.factory.contracts.worker.WorkOrder order,
            ActionRun action) {
        return exactOwnerBinding(outbox, run, order, action)
                && action.status() == ActionRunStatus.WAITING_EXTERNAL;
    }

    private static boolean exactOwnerBinding(
            DispatchOutbox outbox,
            WorkerRunRecord run,
            io.github.flowerjvm.factory.contracts.worker.WorkOrder order,
            ActionRun action) {
        return run != null
                && order != null
                && action != null
                && outbox.tenantId().equals(run.tenantId())
                && outbox.operationId().equals(run.operationId())
                && outbox.aggregateId().equals(run.workerRunId().value())
                && outbox.outboxId().equals(run.dispatchOutboxId().orElse(null))
                && order.tenantId().equals(run.tenantId())
                && order.workOrderId().equals(run.workOrderId())
                && exactActionBinding(run, action);
    }

    private static boolean exactAction(WorkerRunRecord run, ActionRun action) {
        return action.status() == ActionRunStatus.WAITING_EXTERNAL
                && exactActionBinding(run, action);
    }

    private static boolean exactActionBinding(WorkerRunRecord run, ActionRun action) {
        if (run.actionRunId().filter(action.runId()::equals).isEmpty()
                || !WorkerDispatchAction.ACTION_ID.equals(action.actionId())
                || !run.tenantId().value().equals(action.tenantId())
                || !run.operationId().equals(action.externalOperationId())
                || action.attemptToken() == null
                || action.attemptToken().isBlank()
                || run.attemptTokenHash()
                        .filter(WorkerDispatchOperationIds.hashAttemptToken(action.attemptToken())::equals)
                        .isEmpty()) {
            return false;
        }
        try {
            WorkerDispatchInput input = WorkerDispatchInput.from(action.input());
            return input.workOrderId().equals(run.workOrderId())
                    && input.workerRunId().equals(run.workerRunId());
        } catch (RuntimeException invalidInput) {
            return false;
        }
    }

    private static WorkerStatusRequest statusRequest(WorkerRunRecord run, ActionRun action) {
        return new WorkerStatusRequest(
                run.tenantId(), run.workOrderId(), run.workerRunId(), run.operationId(),
                new WorkerAttemptToken(action.attemptToken()));
    }

    private static boolean exactSnapshot(WorkerRunRecord run, WorkerRunSnapshot snapshot) {
        return run.tenantId().equals(snapshot.tenantId())
                && run.workOrderId().equals(snapshot.workOrderId())
                && run.workerRunId().equals(snapshot.workerRunId())
                && run.operationId().equals(snapshot.operationId());
    }

    private static String stableCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{0,127}") ? code : STATUS_UNKNOWN;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static WorkerDispatchPreParkOrphanTransaction noPreParkRecovery() {
        return (observedAt, grace) -> false;
    }
}
