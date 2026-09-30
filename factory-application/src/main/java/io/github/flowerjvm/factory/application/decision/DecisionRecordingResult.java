package io.github.flowerjvm.factory.application.decision;

import java.util.Objects;
import java.util.Optional;

/** Result of a decision request, including the canonical stored record for safe retries. */
public record DecisionRecordingResult(
        DecisionRecordingDisposition disposition,
        Optional<Decision> recordedDecision) {

    public DecisionRecordingResult {
        Objects.requireNonNull(disposition, "disposition");
        recordedDecision = Objects.requireNonNull(recordedDecision, "recordedDecision");
        if (disposition == DecisionRecordingDisposition.CONFLICT && recordedDecision.isPresent()) {
            throw new IllegalArgumentException("a conflicting stored Decision must not be disclosed");
        }
        if (disposition != DecisionRecordingDisposition.CONFLICT && recordedDecision.isEmpty()) {
            throw new IllegalArgumentException("applied and duplicate results require the stored Decision");
        }
    }

    public static DecisionRecordingResult applied(Decision decision) {
        return new DecisionRecordingResult(DecisionRecordingDisposition.APPLIED, Optional.of(decision));
    }

    public static DecisionRecordingResult duplicate(Decision decision) {
        return new DecisionRecordingResult(DecisionRecordingDisposition.DUPLICATE, Optional.of(decision));
    }

    public static DecisionRecordingResult conflict() {
        return new DecisionRecordingResult(DecisionRecordingDisposition.CONFLICT, Optional.empty());
    }
}
