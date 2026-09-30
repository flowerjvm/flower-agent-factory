package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.application.action.WorkerDispatchOperationIds;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import io.github.flowerjvm.factory.contracts.worker.WorkerRetryDisposition;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.RetryDisposition;
import io.github.flowerjvm.flower.action.runtime.run.ActionRun;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Canonical, non-secret Worker completion projection persisted in the owning ActionRun result. */
public final class WorkerCompletionActionProjection {
    private static final String WORKER_RUN_ID = "workerRunId";
    private static final String OPERATION_ID = "operationId";
    private static final String WORKER_STATUS = "workerStatus";
    private static final String RESULT_ARTIFACT_REF = "resultArtifactManifestRef";
    private static final String RESULT_HASH = "resultHash";
    private static final String WORKER_CODE = "workerCode";
    private static final String WORKER_MESSAGE = "workerMessage";
    private static final String WORKER_RETRY = "workerRetryDisposition";
    private static final String COMPLETED_AT = "completedAt";

    public static final String WORKER_RUN_TIMED_OUT = "WORKER_RUN_TIMED_OUT";

    private WorkerCompletionActionProjection() {}

    /** Stores enough canonical data to finish the WorkerRun CAS after a host restart. */
    public static ActionExecutionResult toActionResult(WorkerCompletion completion) {
        Objects.requireNonNull(completion, "completion");
        Map<String, Object> output = new HashMap<>();
        output.put(WORKER_RUN_ID, completion.workerRunId().value());
        output.put(OPERATION_ID, completion.operationId());
        output.put(WORKER_STATUS, completion.terminalStatus().name());
        completion.resultArtifactManifestRef()
                .ifPresent(reference -> output.put(RESULT_ARTIFACT_REF, reference.value()));
        completion.resultHash().ifPresent(hash -> output.put(RESULT_HASH, hash.sha256()));
        output.put(WORKER_CODE, completion.code());
        output.put(WORKER_MESSAGE, completion.message());
        output.put(WORKER_RETRY, completion.retryDisposition().name());
        output.put(COMPLETED_AT, canonicalTimestamp(completion.completedAt()).toString());
        ActionExecutionStatus status = completion.terminalStatus() == WorkerRunStatus.SUCCEEDED
                ? ActionExecutionStatus.SUCCEEDED
                : ActionExecutionStatus.FAILED;
        return new ActionExecutionResult(
                status,
                completion.code(),
                completion.message(),
                Map.copyOf(output),
                RetryDisposition.valueOf(completion.retryDisposition().name()));
    }

    /** Competes with an external callback for the same first-terminal ActionRun truth. */
    public static ActionExecutionResult toTimeoutActionResult(WorkerRunRecord workerRun) {
        return toTimeoutActionResult(workerRun, workerRun.deadlineAt());
    }

    /**
     * Competes with an external callback at the earliest durable enclosing deadline.
     *
     * <p>The caller may supply a BuildSession or WorkOrder cutoff that is earlier than the
     * WorkerRun deadline. The cutoff itself is persisted in the Action result so crash recovery
     * can project the same first-terminal truth without reconstructing the parent ledger.
     */
    public static ActionExecutionResult toTimeoutActionResult(
            WorkerRunRecord workerRun,
            Instant effectiveDeadline) {
        Objects.requireNonNull(workerRun, "workerRun");
        Instant canonicalDeadline = canonicalTimestamp(
                Objects.requireNonNull(effectiveDeadline, "effectiveDeadline"));
        if (canonicalDeadline.isAfter(canonicalTimestamp(workerRun.deadlineAt()))) {
            throw new IllegalArgumentException("effectiveDeadline must not exceed the WorkerRun deadline");
        }
        if (canonicalDeadline.isBefore(canonicalTimestamp(workerRun.updatedAt()))) {
            throw new IllegalArgumentException("effectiveDeadline must not predate the WorkerRun state");
        }
        return toActionResult(new TerminalOutcome(
                workerRun.workerRunId(),
                workerRun.operationId(),
                WorkerRunStatus.TIMED_OUT,
                Optional.empty(),
                Optional.empty(),
                WORKER_RUN_TIMED_OUT,
                "Effective worker execution deadline elapsed",
                WorkerRetryDisposition.MANUAL_REVIEW,
                canonicalDeadline));
    }

    /** First-terminal Action result for an external effect whose state cannot be proven safely. */
    public static ActionExecutionResult toManualReviewActionResult(
            WorkerRunRecord workerRun, String code, String message, Instant observedAt) {
        Objects.requireNonNull(workerRun, "workerRun");
        return toActionResult(new TerminalOutcome(
                workerRun.workerRunId(),
                workerRun.operationId(),
                WorkerRunStatus.MANUAL_REVIEW,
                Optional.empty(),
                Optional.empty(),
                requireText(code, "code"),
                requireText(message, "message"),
                WorkerRetryDisposition.MANUAL_REVIEW,
                canonicalTimestamp(observedAt)));
    }

    /**
     * Reconstructs an authenticated completion only from the terminal owner ActionRun.
     * Malformed, cross-ledger, or legacy results remain untrusted and return empty.
     */
    public static Optional<WorkerCompletion> fromTerminalAction(
            ActionRun actionRun,
            WorkerRunRecord workerRun) {
        return fromTerminalActionOutcome(actionRun, workerRun)
                .filter(outcome -> outcome.status() == WorkerRunStatus.SUCCEEDED
                        || outcome.status() == WorkerRunStatus.FAILED)
                .map(outcome -> new WorkerCompletion(
                        workerRun.tenantId(),
                        outcome.workerRunId(),
                        outcome.operationId(),
                        actionRun.attemptToken(),
                        outcome.status(),
                        outcome.resultArtifactManifestRef(),
                        outcome.resultHash(),
                        outcome.code(),
                        outcome.message(),
                        outcome.retryDisposition(),
                        outcome.completedAt()));
    }

    public static Optional<TerminalOutcome> fromTerminalActionOutcome(
            ActionRun actionRun,
            WorkerRunRecord workerRun) {
        Objects.requireNonNull(actionRun, "actionRun");
        Objects.requireNonNull(workerRun, "workerRun");
        if (!actionRun.status().isTerminal()
                || actionRun.result() == null
                || workerRun.actionRunId().filter(actionRun.runId()::equals).isEmpty()
                || workerRun.attemptTokenHash()
                        .filter(WorkerDispatchOperationIds.hashAttemptToken(actionRun.attemptToken())::equals)
                        .isEmpty()) {
            return Optional.empty();
        }
        return fromActionResult(actionRun.result(), workerRun);
    }

    /** Parses the canonical first-terminal Action result, including the internal timeout result. */
    public static Optional<TerminalOutcome> fromActionResult(
            ActionExecutionResult result,
            WorkerRunRecord workerRun) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(workerRun, "workerRun");
        if (result.status() != ActionExecutionStatus.SUCCEEDED
                && result.status() != ActionExecutionStatus.FAILED) {
            return Optional.empty();
        }
        Map<String, Object> output = result.output();
        try {
            String workerRunId = text(output, WORKER_RUN_ID);
            String operationId = text(output, OPERATION_ID);
            WorkerRunStatus workerStatus = WorkerRunStatus.valueOf(text(output, WORKER_STATUS));
            String code = text(output, WORKER_CODE);
            String message = text(output, WORKER_MESSAGE);
            WorkerRetryDisposition retry = WorkerRetryDisposition.valueOf(text(output, WORKER_RETRY));
            Instant completedAt = Instant.parse(text(output, COMPLETED_AT));
            Optional<ArtifactReference> resultRef = optionalText(output, RESULT_ARTIFACT_REF)
                    .map(ArtifactReference::new);
            Optional<ContentHash> resultHash = optionalText(output, RESULT_HASH).map(ContentHash::new);
            boolean supportedStatus = workerStatus == WorkerRunStatus.SUCCEEDED
                    || workerStatus == WorkerRunStatus.FAILED
                    || workerStatus == WorkerRunStatus.TIMED_OUT
                    || workerStatus == WorkerRunStatus.MANUAL_REVIEW;
            boolean actionStatusMatches = workerStatus == WorkerRunStatus.SUCCEEDED
                    ? result.terminalSuccess()
                    : result.status() == ActionExecutionStatus.FAILED;
            if (!workerRun.workerRunId().value().equals(workerRunId)
                    || !workerRun.operationId().equals(operationId)
                    || resultRef.isPresent() != resultHash.isPresent()
                    || (workerStatus == WorkerRunStatus.SUCCEEDED && resultHash.isEmpty())
                    || (workerStatus == WorkerRunStatus.TIMED_OUT
                            && (resultRef.isPresent()
                                    || completedAt.isAfter(canonicalTimestamp(workerRun.deadlineAt()))))
                    || !supportedStatus
                    || !actionStatusMatches
                    || !code.equals(result.code())
                    || !message.equals(result.message())
                    || RetryDisposition.valueOf(retry.name()) != result.retryDisposition()) {
                return Optional.empty();
            }
            return Optional.of(new TerminalOutcome(
                    new WorkerRunId(workerRunId),
                    operationId,
                    workerStatus,
                    resultRef,
                    resultHash,
                    code,
                    message,
                    retry,
                    completedAt));
        } catch (DateTimeException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static ActionExecutionResult toActionResult(TerminalOutcome outcome) {
        Map<String, Object> output = new HashMap<>();
        output.put(WORKER_RUN_ID, outcome.workerRunId().value());
        output.put(OPERATION_ID, outcome.operationId());
        output.put(WORKER_STATUS, outcome.status().name());
        outcome.resultArtifactManifestRef()
                .ifPresent(reference -> output.put(RESULT_ARTIFACT_REF, reference.value()));
        outcome.resultHash().ifPresent(hash -> output.put(RESULT_HASH, hash.sha256()));
        output.put(WORKER_CODE, outcome.code());
        output.put(WORKER_MESSAGE, outcome.message());
        output.put(WORKER_RETRY, outcome.retryDisposition().name());
        output.put(COMPLETED_AT, canonicalTimestamp(outcome.completedAt()).toString());
        return new ActionExecutionResult(
                outcome.status() == WorkerRunStatus.SUCCEEDED
                        ? ActionExecutionStatus.SUCCEEDED
                        : ActionExecutionStatus.FAILED,
                outcome.code(),
                outcome.message(),
                Map.copyOf(output),
                RetryDisposition.valueOf(outcome.retryDisposition().name()));
    }

    /** Internal cross-ledger terminal truth; callback DTOs remain limited to success/failure. */
    public record TerminalOutcome(
            WorkerRunId workerRunId,
            String operationId,
            WorkerRunStatus status,
            Optional<ArtifactReference> resultArtifactManifestRef,
            Optional<ContentHash> resultHash,
            String code,
            String message,
            WorkerRetryDisposition retryDisposition,
            Instant completedAt) {

        public TerminalOutcome {
            Objects.requireNonNull(workerRunId, "workerRunId");
            operationId = requireText(operationId, "operationId");
            Objects.requireNonNull(status, "status");
            resultArtifactManifestRef = Objects.requireNonNull(
                    resultArtifactManifestRef, "resultArtifactManifestRef");
            resultHash = Objects.requireNonNull(resultHash, "resultHash");
            code = requireText(code, "code");
            message = requireText(message, "message");
            Objects.requireNonNull(retryDisposition, "retryDisposition");
            Objects.requireNonNull(completedAt, "completedAt");
        }

        public WorkerRunRecord applyTo(WorkerRunRecord workerRun) {
            Objects.requireNonNull(workerRun, "workerRun");
            if (!workerRun.workerRunId().equals(workerRunId)
                    || !workerRun.operationId().equals(operationId)) {
                throw new IllegalArgumentException("Worker terminal outcome is outside the WorkerRun scope");
            }
            if (status == WorkerRunStatus.MANUAL_REVIEW) {
                return workerRun.manualReview(code, message, completedAt);
            }
            return workerRun.complete(
                    status,
                    resultArtifactManifestRef,
                    resultHash,
                    code,
                    message,
                    retryDisposition,
                    completedAt);
        }
    }

    private static String text(Map<String, Object> output, String key) {
        Object value = output.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("missing completion projection field: " + key);
        }
        return text;
    }

    private static Optional<String> optionalText(Map<String, Object> output, String key) {
        Object value = output.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("invalid completion projection field: " + key);
        }
        return Optional.of(text);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** PostgreSQL and H2 durable timestamp precision shared by both ledgers. */
    public static Instant canonicalTimestamp(Instant value) {
        return Objects.requireNonNull(value, "value").truncatedTo(ChronoUnit.MICROS);
    }
}
