package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchInput;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.application.candidate.CandidateIngestionService;
import io.github.flowerjvm.factory.application.candidate.CandidateIngestionService.PreparedResult;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.ActionRunStatus;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded restart-safe processor for authenticated callback/status completion envelopes. */
public final class WorkerCallbackProcessor {
    public static final String APPLIED = "WORKER_CALLBACK_APPLIED";
    public static final String SUPERSEDED = "WORKER_CALLBACK_SUPERSEDED";
    public static final String INVALID_OWNER = "WORKER_CALLBACK_OWNER_INVALID";
    public static final String INVALID_RESULT = "WORKER_RESULT_INVALID";
    public static final String AWAITING_ACCEPTANCE = "WORKER_CALLBACK_AWAITING_ACCEPTANCE";
    public static final String ACCEPTANCE_ORPHANED = "WORKER_CALLBACK_ACCEPTANCE_ORPHANED";
    public static final String DEADLINE_EXCEEDED = "WORKER_CALLBACK_DEADLINE_EXCEEDED";
    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);
    public static final Duration DEFAULT_DEFER = Duration.ofSeconds(5);

    private final WorkerCallbackInboxRepository inbox;
    private final WorkerProtocolArtifacts artifacts;
    private final WorkOrderRepository workOrders;
    private final WorkerRunRepository workerRuns;
    private final RunStore runStore;
    private final CandidateIngestionService candidateIngestion;
    private final WorkerCompletionService completions;
    private final WorkerCompletionClassifier classifier;
    private final Clock clock;
    private final Duration lease;
    private final Duration defer;

    public WorkerCallbackProcessor(
            WorkerCallbackInboxRepository inbox,
            WorkerProtocolArtifacts artifacts,
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            RunStore runStore,
            CandidateIngestionService candidateIngestion,
            WorkerCompletionService completions,
            WorkerCompletionClassifier classifier,
            Clock clock) {
        this(
                inbox, artifacts, workOrders, workerRuns, runStore, candidateIngestion, completions,
                classifier, clock, DEFAULT_LEASE, DEFAULT_DEFER);
    }

    public WorkerCallbackProcessor(
            WorkerCallbackInboxRepository inbox,
            WorkerProtocolArtifacts artifacts,
            WorkOrderRepository workOrders,
            WorkerRunRepository workerRuns,
            RunStore runStore,
            CandidateIngestionService candidateIngestion,
            WorkerCompletionService completions,
            WorkerCompletionClassifier classifier,
            Clock clock,
            Duration lease,
            Duration defer) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
        this.workerRuns = Objects.requireNonNull(workerRuns, "workerRuns");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.candidateIngestion = Objects.requireNonNull(candidateIngestion, "candidateIngestion");
        this.completions = Objects.requireNonNull(completions, "completions");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lease = positive(lease, "lease");
        this.defer = positive(defer, "defer");
    }

    public boolean tickOnce() {
        Instant now = clock.instant();
        String token = UUID.randomUUID().toString();
        var claimed = inbox.claimExpired(now, lease, token);
        if (claimed.isEmpty()) {
            token = UUID.randomUUID().toString();
            claimed = inbox.claimNext(now, lease, token);
        }
        if (claimed.isEmpty()) {
            return false;
        }
        process(claimed.orElseThrow(), token, now);
        return true;
    }

    public int drain(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be positive");
        }
        int processed = 0;
        while (processed < maximum && tickOnce()) {
            processed++;
        }
        return processed;
    }

    private void process(WorkerCallbackInboxEntry entry, String claimToken, Instant now) {
        CodingWorkerCompletionPayload payload;
        try {
            payload = artifacts.readCompletion(entry);
        } catch (RuntimeException malformedStoredPayload) {
            transition(entry, entry.manualReview(claimToken, INVALID_RESULT, now));
            return;
        }
        WorkerRunRecord workerRun = workerRuns.find(entry.tenantId(), entry.workerRunId()).orElse(null);
        WorkOrder order = workOrders.find(entry.tenantId(), entry.workOrderId()).orElse(null);
        ActionRun actionRun = workerRun == null
                ? null
                : workerRun.actionRunId().flatMap(runStore::find).orElse(null);
        if (!exactOwner(entry, payload, order, workerRun, actionRun)) {
            transition(entry, entry.manualReview(claimToken, INVALID_OWNER, now));
            return;
        }
        if (workerRun.status() == WorkerRunStatus.DISPATCHING
                || actionRun.status() == ActionRunStatus.RUNNING) {
            if (!entry.receivedAt().isBefore(workerRun.deadlineAt())) {
                transition(entry, entry.rejected(claimToken, DEADLINE_EXCEEDED, now));
            } else if (!now.isBefore(workerRun.deadlineAt())) {
                // The callback arrived on time, but dispatch acceptance never became durable by
                // the exact cutoff. Keep ActionRun canonical and surface the orphan boundary
                // instead of deferring this inbox row forever after restart.
                transition(entry, entry.manualReview(claimToken, ACCEPTANCE_ORPHANED, now));
            } else {
                transition(entry, entry.defer(claimToken, AWAITING_ACCEPTANCE, now, now.plus(defer)));
            }
            return;
        }
        if (actionRun.status() == ActionRunStatus.CANCELLED) {
            transition(entry, entry.superseded(claimToken, SUPERSEDED, now));
            return;
        }
        if (!entry.receivedAt().isBefore(workerRun.deadlineAt())) {
            transition(entry, entry.rejected(claimToken, DEADLINE_EXCEEDED, now));
            return;
        }

        WorkerCompletionClassification classification = classifier.classify(payload);
        PreparedResult prepared = null;
        if (classification.terminalStatus() == WorkerRunStatus.SUCCEEDED) {
            try {
                prepared = candidateIngestion.validateAndPromote(
                        order, workerRun, payload.result().orElseThrow(), entry.receivedAt());
            } catch (IllegalArgumentException invalidWorkerResult) {
                classification = new WorkerCompletionClassification(
                        WorkerRunStatus.FAILED,
                        INVALID_RESULT,
                        "Coding Worker result failed the trusted manifest and artifact gate",
                        WorkerRetryDisposition.MANUAL_REVIEW);
            }
        }

        WorkerCompletion completion = new WorkerCompletion(
                entry.tenantId(),
                entry.workerRunId(),
                entry.operationId(),
                actionRun.attemptToken(),
                classification.terminalStatus(),
                classification.terminalStatus() == WorkerRunStatus.SUCCEEDED
                        ? Optional.of(payload.result().orElseThrow().primaryArtifactRef())
                        : Optional.empty(),
                classification.terminalStatus() == WorkerRunStatus.SUCCEEDED
                        ? Optional.of(payload.result().orElseThrow().primaryResultHash())
                        : Optional.empty(),
                classification.code(),
                classification.message(),
                classification.retryDisposition(),
                entry.receivedAt());

        WorkerCompletionDisposition disposition;
        try {
            disposition = completions.completeFromTrustedInboxReceipt(completion);
        } catch (RuntimeException terminalConflict) {
            ActionRun canonical = runStore.find(actionRun.runId()).orElse(null);
            if (canonical != null && canonical.status() == ActionRunStatus.CANCELLED) {
                transition(entry, entry.superseded(claimToken, SUPERSEDED, now));
                return;
            }
            // A non-cancel terminal Action may already have won while the following WorkerRun CAS
            // failed transiently. Keep the inbox claim retryable so the canonical Action result is
            // projected and successful candidate ingestion is not lost.
            throw terminalConflict;
        }
        if (disposition == WorkerCompletionDisposition.STALE_OR_CONFLICT) {
            transition(entry, entry.superseded(claimToken, SUPERSEDED, now));
            return;
        }

        WorkerRunRecord canonical = workerRuns.find(entry.tenantId(), entry.workerRunId())
                .orElseThrow(() -> new IllegalStateException("WorkerRun disappeared after completion"));
        if (canonical.status() == WorkerRunStatus.SUCCEEDED) {
            if (prepared == null) {
                prepared = candidateIngestion.validateAndPromote(
                        order, workerRun, payload.result().orElseThrow(), entry.receivedAt());
            }
            candidateIngestion.persistAfterCanonicalSuccess(prepared, canonical);
        }
        transition(entry, entry.applied(claimToken, APPLIED, clock.instant()));
    }

    private static boolean exactOwner(
            WorkerCallbackInboxEntry entry,
            CodingWorkerCompletionPayload payload,
            WorkOrder order,
            WorkerRunRecord workerRun,
            ActionRun actionRun) {
        if (order == null || workerRun == null || actionRun == null
                || !entry.tenantId().equals(order.tenantId())
                || !entry.tenantId().equals(workerRun.tenantId())
                || !entry.workerBindingId().equals(workerRun.workerBindingId())
                || !entry.workOrderId().equals(order.workOrderId())
                || !entry.workOrderId().equals(workerRun.workOrderId())
                || !entry.workerRunId().equals(workerRun.workerRunId())
                || !entry.operationId().equals(workerRun.operationId())
                || !entry.eventId().equals(payload.eventId())
                || !entry.workOrderId().equals(payload.workOrderId())
                || !entry.workerRunId().equals(payload.workerRunId())
                || !entry.operationId().equals(payload.operationId())
                || workerRun.actionRunId().filter(actionRun.runId()::equals).isEmpty()
                || !WorkerDispatchAction.ACTION_ID.equals(actionRun.actionId())
                || !entry.tenantId().value().equals(actionRun.tenantId())
                || actionRun.attemptToken() == null
                || actionRun.attemptToken().isBlank()
                || !entry.attemptTokenHash().equals(
                        WorkerDispatchOperationIds.hashAttemptToken(actionRun.attemptToken()))
                || workerRun.attemptTokenHash().filter(entry.attemptTokenHash()::equals).isEmpty()
                || !WorkerAttemptProofs.matches(
                        actionRun.attemptToken(), entry.eventId(), entry.operationId(),
                        entry.workerRunId(), payload.attemptProof())) {
            return false;
        }
        try {
            WorkerDispatchInput input = WorkerDispatchInput.from(actionRun.input());
            return input.workOrderId().equals(entry.workOrderId())
                    && input.workerRunId().equals(entry.workerRunId());
        } catch (RuntimeException invalidInput) {
            return false;
        }
    }

    private void transition(WorkerCallbackInboxEntry expected, WorkerCallbackInboxEntry next) {
        inbox.compareAndSet(expected, next);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
