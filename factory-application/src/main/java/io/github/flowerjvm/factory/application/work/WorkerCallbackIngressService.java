package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.application.action.WorkerDispatchAction;
import io.github.flowerjvm.factory.application.action.WorkerDispatchInput;
import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Verifies trusted binding ownership and the event-bound attempt proof before staging a tokenless
 * callback. It never calls the Coding Worker or writes ActionRun directly.
 */
public final class WorkerCallbackIngressService {
    public static final String RECEIVED = "WORKER_CALLBACK_RECEIVED";
    public static final String REJECTED = "WORKER_CALLBACK_REJECTED";
    public static final String DUPLICATE = "WORKER_CALLBACK_DUPLICATE";
    public static final String MALFORMED = "WORKER_CALLBACK_MALFORMED";
    public static final String SCOPE_MISMATCH = "WORKER_CALLBACK_SCOPE_MISMATCH";
    public static final String STALE_ATTEMPT = "WORKER_CALLBACK_STALE_ATTEMPT";
    public static final String EVENT_CONFLICT = "WORKER_CALLBACK_EVENT_CONFLICT";

    private final WorkerProtocolArtifacts artifacts;
    private final WorkerRunRepository workerRuns;
    private final RunStore runStore;
    private final WorkerCallbackInboxRepository inbox;
    private final WorkerCallbackSecurityAudit audit;
    private final Clock clock;

    public WorkerCallbackIngressService(
            WorkerProtocolArtifacts artifacts,
            WorkerRunRepository workerRuns,
            RunStore runStore,
            WorkerCallbackInboxRepository inbox,
            WorkerCallbackSecurityAudit audit,
            Clock clock) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.workerRuns = Objects.requireNonNull(workerRuns, "workerRuns");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WorkerCallbackReceipt receive(WorkerCallbackCommand command) {
        return receive(command, clock.instant(), false);
    }

    /**
     * Stages a terminal envelope recovered from the authenticated durable worker journal.
     *
     * <p>The timestamp is host-journal authority, never a field copied from the worker payload.
     * Keeping it distinct from {@link #receive(WorkerCallbackCommand)} prevents an HTTP caller from
     * selecting callback receipt time.
     */
    public WorkerCallbackReceipt receiveFromTrustedStatusJournal(
            WorkerCallbackCommand command, Instant trustedReceivedAt) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(trustedReceivedAt, "trustedReceivedAt");
        Instant now = clock.instant();
        if (trustedReceivedAt.isAfter(now)) {
            throw new IllegalArgumentException("trusted journal receipt time must not be in the future");
        }
        return receive(command, trustedReceivedAt, true);
    }

    private WorkerCallbackReceipt receive(
            WorkerCallbackCommand command, Instant receivedAt, boolean trustedStatusJournal) {
        Objects.requireNonNull(command, "command");
        CodingWorkerCompletionPayload payload;
        try {
            payload = artifacts.readCompletion(command);
        } catch (RuntimeException malformed) {
            reject(command.trustedContext(), Optional.empty(), Optional.empty(), MALFORMED, receivedAt);
            return rejected();
        }

        TrustedWorkerCallbackContext trusted = command.trustedContext();
        WorkerRunRecord workerRun = workerRuns.find(trusted.tenantId(), payload.workerRunId()).orElse(null);
        ActionRun actionRun = workerRun == null
                ? null
                : workerRun.actionRunId().flatMap(runStore::find).orElse(null);
        if (!exactScope(trusted, payload, workerRun, actionRun)) {
            reject(
                    trusted,
                    Optional.of(payload.eventId()),
                    Optional.of(payload.workerRunId().value()),
                    SCOPE_MISMATCH,
                    receivedAt);
            return rejected();
        }
        if (!validAttempt(payload, workerRun, actionRun)) {
            reject(
                    trusted,
                    Optional.of(payload.eventId()),
                    Optional.of(payload.workerRunId().value()),
                    STALE_ATTEMPT,
                    receivedAt);
            return rejected();
        }
        if (trustedStatusJournal
                && (workerRun.startedAt().filter(started -> !receivedAt.isBefore(started)).isEmpty()
                        || !receivedAt.isBefore(workerRun.deadlineAt()))) {
            reject(
                    trusted,
                    Optional.of(payload.eventId()),
                    Optional.of(payload.workerRunId().value()),
                    MALFORMED,
                    clock.instant());
            return rejected();
        }

        String callbackId = WorkerCallbackIds.derive(
                trusted.tenantId(), trusted.workerBindingId(), payload.eventId(), command.payloadHash());
        var existing = inbox.findByEvent(trusted.tenantId(), trusted.workerBindingId(), payload.eventId());
        if (existing.isPresent()) {
            if (sameDelivery(existing.orElseThrow(), callbackId, command)) {
                acceptedAudit(trusted, payload, DUPLICATE, receivedAt);
                return new WorkerCallbackReceipt(
                        WorkerCallbackReceipt.Status.DUPLICATE, DUPLICATE, Optional.of(callbackId));
            }
            reject(
                    trusted,
                    Optional.of(payload.eventId()),
                    Optional.of(payload.workerRunId().value()),
                    EVENT_CONFLICT,
                    receivedAt);
            return rejected();
        }

        WorkerCallbackInboxEntry entry = WorkerCallbackInboxEntry.received(
                callbackId,
                trusted.tenantId(),
                trusted.workerBindingId(),
                payload.eventId(),
                payload.workOrderId(),
                payload.workerRunId(),
                payload.operationId(),
                workerRun.attemptTokenHash().orElseThrow(),
                command.payloadArtifactRef(),
                command.payloadHash(),
                receivedAt);
        try {
            inbox.create(entry);
        } catch (RuntimeException createRace) {
            var winner = inbox.findByEvent(trusted.tenantId(), trusted.workerBindingId(), payload.eventId());
            if (winner.isEmpty() || !sameDelivery(winner.orElseThrow(), callbackId, command)) {
                throw createRace;
            }
            acceptedAudit(trusted, payload, DUPLICATE, receivedAt);
            return new WorkerCallbackReceipt(
                    WorkerCallbackReceipt.Status.DUPLICATE, DUPLICATE, Optional.of(callbackId));
        }
        acceptedAudit(trusted, payload, RECEIVED, receivedAt);
        return new WorkerCallbackReceipt(
                WorkerCallbackReceipt.Status.ACCEPTED, RECEIVED, Optional.of(callbackId));
    }

    private static boolean exactScope(
            TrustedWorkerCallbackContext trusted,
            CodingWorkerCompletionPayload payload,
            WorkerRunRecord workerRun,
            ActionRun actionRun) {
        if (workerRun == null
                || actionRun == null
                || !trusted.tenantId().equals(workerRun.tenantId())
                || !trusted.workerBindingId().equals(workerRun.workerBindingId())
                || !payload.workOrderId().equals(workerRun.workOrderId())
                || !payload.operationId().equals(workerRun.operationId())
                || !WorkerDispatchAction.ACTION_ID.equals(actionRun.actionId())
                || !trusted.tenantId().value().equals(actionRun.tenantId())
                || workerRun.actionRunId().filter(actionRun.runId()::equals).isEmpty()) {
            return false;
        }
        try {
            WorkerDispatchInput input = WorkerDispatchInput.from(actionRun.input());
            return input.workOrderId().equals(payload.workOrderId())
                    && input.workerRunId().equals(payload.workerRunId())
                    && payload.result().map(result -> result.workOrderId().equals(payload.workOrderId())
                                    && result.workerRunId().equals(payload.workerRunId())
                                    && result.operationId().equals(payload.operationId()))
                            .orElse(true);
        } catch (RuntimeException invalidActionInput) {
            return false;
        }
    }

    private static boolean validAttempt(
            CodingWorkerCompletionPayload payload, WorkerRunRecord workerRun, ActionRun actionRun) {
        if (actionRun.attemptToken() == null || actionRun.attemptToken().isBlank()
                || workerRun.attemptTokenHash().isEmpty()
                || !workerRun.attemptTokenHash().orElseThrow().equals(
                        WorkerDispatchOperationIds.hashAttemptToken(actionRun.attemptToken()))) {
            return false;
        }
        return WorkerAttemptProofs.matches(
                actionRun.attemptToken(),
                payload.eventId(),
                payload.operationId(),
                payload.workerRunId(),
                payload.attemptProof());
    }

    private static boolean sameDelivery(
            WorkerCallbackInboxEntry existing, String callbackId, WorkerCallbackCommand command) {
        return existing.callbackId().equals(callbackId)
                && existing.payloadArtifactRef().equals(command.payloadArtifactRef())
                && existing.payloadHash().equals(command.payloadHash());
    }

    private void acceptedAudit(
            TrustedWorkerCallbackContext trusted,
            CodingWorkerCompletionPayload payload,
            String code,
            Instant at) {
        audit.record(new WorkerCallbackAuditEvent(
                Optional.of(trusted.tenantId()),
                trusted.workerBindingId(),
                Optional.of(trusted.authenticatedPrincipalRef()),
                Optional.of(payload.eventId()),
                Optional.of(payload.workerRunId().value()),
                code,
                true,
                at));
    }

    private void reject(
            TrustedWorkerCallbackContext trusted,
            Optional<String> eventId,
            Optional<String> workerRunId,
            String code,
            Instant at) {
        audit.record(new WorkerCallbackAuditEvent(
                Optional.of(trusted.tenantId()),
                trusted.workerBindingId(),
                Optional.of(trusted.authenticatedPrincipalRef()),
                eventId,
                workerRunId,
                code,
                false,
                at));
    }

    private static WorkerCallbackReceipt rejected() {
        return new WorkerCallbackReceipt(
                WorkerCallbackReceipt.Status.REJECTED, REJECTED, Optional.empty());
    }
}
