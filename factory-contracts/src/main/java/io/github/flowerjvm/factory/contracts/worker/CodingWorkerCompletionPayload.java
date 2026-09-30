package io.github.flowerjvm.factory.contracts.worker;

import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.ids.WorkerRunId;
import java.util.Objects;
import java.util.Optional;

/**
 * Strict tokenless callback body. The attempt proof is bound to this event and is not the raw
 * Action attempt token.
 */
public record CodingWorkerCompletionPayload(
        String schemaVersion,
        String eventId,
        WorkOrderId workOrderId,
        WorkerRunId workerRunId,
        String operationId,
        String attemptProof,
        WorkerRunStatus terminalStatus,
        Optional<CodingWorkerResultManifest> result,
        String workerCode) {

    public CodingWorkerCompletionPayload {
        if (!WorkerProtocol.COMPLETION_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported Coding Worker completion schemaVersion");
        }
        eventId = requireText(eventId, "eventId", 128);
        Objects.requireNonNull(workOrderId, "workOrderId");
        Objects.requireNonNull(workerRunId, "workerRunId");
        operationId = requireText(operationId, "operationId", 256);
        attemptProof = requireLowerHex(attemptProof, "attemptProof", 64);
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        if (terminalStatus != WorkerRunStatus.SUCCEEDED && terminalStatus != WorkerRunStatus.FAILED) {
            throw new IllegalArgumentException("completion supports only SUCCEEDED or FAILED");
        }
        result = Objects.requireNonNull(result, "result");
        if ((terminalStatus == WorkerRunStatus.SUCCEEDED) != result.isPresent()) {
            throw new IllegalArgumentException("only a successful completion carries a result manifest");
        }
        workerCode = requireStableCode(workerCode);
    }

    private static String requireStableCode(String value) {
        String code = requireText(value, "workerCode", 128);
        if (!code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("workerCode must be a stable uppercase code");
        }
        return code;
    }

    private static String requireLowerHex(String value, String name, int length) {
        if (value == null || value.length() != length || !value.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException(name + " must be lowercase hexadecimal text");
        }
        return value;
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
        }
        return value;
    }
}
