package io.github.flowerjvm.factory.contracts.worker;

import java.util.Objects;
import java.util.Optional;

/** Recovery-safe status observation; UNKNOWN never aliases a terminal Worker failure. */
public record WorkerStatusObservation(
        WorkerLookupState lookupState,
        Optional<WorkerRunSnapshot> snapshot,
        Optional<CodingWorkerCompletionPayload> terminalPayload,
        Optional<String> stableCode) {

    public WorkerStatusObservation {
        Objects.requireNonNull(lookupState, "lookupState");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        terminalPayload = Objects.requireNonNull(terminalPayload, "terminalPayload");
        stableCode = Objects.requireNonNull(stableCode, "stableCode");
        if ((lookupState == WorkerLookupState.FOUND) != snapshot.isPresent()) {
            throw new IllegalArgumentException("only FOUND observations carry a Worker snapshot");
        }
        if (terminalPayload.isPresent()
                && (lookupState != WorkerLookupState.FOUND
                        || !snapshot.orElseThrow().status().isTerminal())) {
            throw new IllegalArgumentException("terminal payload requires a terminal FOUND snapshot");
        }
        if (lookupState != WorkerLookupState.FOUND && terminalPayload.isPresent()) {
            throw new IllegalArgumentException("non-FOUND observations cannot carry completion payloads");
        }
    }

    public static WorkerStatusObservation found(WorkerRunSnapshot snapshot) {
        return new WorkerStatusObservation(
                WorkerLookupState.FOUND, Optional.of(snapshot), Optional.empty(), Optional.empty());
    }

    public static WorkerStatusObservation terminal(
            WorkerRunSnapshot snapshot, CodingWorkerCompletionPayload payload) {
        return new WorkerStatusObservation(
                WorkerLookupState.FOUND, Optional.of(snapshot), Optional.of(payload), Optional.empty());
    }

    public static WorkerStatusObservation notFound() {
        return new WorkerStatusObservation(
                WorkerLookupState.NOT_FOUND, Optional.empty(), Optional.empty(),
                Optional.of("CODING_WORKER_OPERATION_NOT_FOUND"));
    }

    public static WorkerStatusObservation unavailable(String stableCode) {
        return absent(WorkerLookupState.UNAVAILABLE, stableCode);
    }

    public static WorkerStatusObservation unknown(String stableCode) {
        return absent(WorkerLookupState.UNKNOWN, stableCode);
    }

    private static WorkerStatusObservation absent(WorkerLookupState state, String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("stableCode must be a bounded uppercase code");
        }
        return new WorkerStatusObservation(
                state, Optional.empty(), Optional.empty(), Optional.of(code));
    }
}
