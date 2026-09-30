package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import java.util.Map;
import java.util.Set;

/** Strict v1 verification Action input; unknown fields are rejected. */
public record VerificationRunInput(
        VerificationRunId verificationRunId,
        CandidateId candidateId,
        long expectedVerificationRunVersion) {

    private static final Set<String> FIELDS = Set.of(
            VerificationRunAction.VERIFICATION_RUN_ID,
            VerificationRunAction.CANDIDATE_ID,
            VerificationRunAction.EXPECTED_VERIFICATION_RUN_VERSION);

    public VerificationRunInput {
        if (verificationRunId == null || candidateId == null) {
            throw new IllegalArgumentException("verificationRunId and candidateId are required");
        }
        if (expectedVerificationRunVersion < 0) {
            throw new IllegalArgumentException("expectedVerificationRunVersion must not be negative");
        }
    }

    public static VerificationRunInput from(Map<String, Object> input) {
        if (input == null || !input.keySet().equals(FIELDS)) {
            throw new IllegalArgumentException("input must contain exactly " + FIELDS);
        }
        String verificationRunId = requireText(
                input.get(VerificationRunAction.VERIFICATION_RUN_ID),
                VerificationRunAction.VERIFICATION_RUN_ID);
        String candidateId = requireText(
                input.get(VerificationRunAction.CANDIDATE_ID),
                VerificationRunAction.CANDIDATE_ID);
        Object expectedVersion = input.get(VerificationRunAction.EXPECTED_VERIFICATION_RUN_VERSION);
        if (!(expectedVersion instanceof Byte
                || expectedVersion instanceof Short
                || expectedVersion instanceof Integer
                || expectedVersion instanceof Long)) {
            throw new IllegalArgumentException("expectedVerificationRunVersion must be an integer");
        }
        return new VerificationRunInput(
                new VerificationRunId(verificationRunId),
                new CandidateId(candidateId),
                ((Number) expectedVersion).longValue());
    }

    public Map<String, Object> toMap() {
        return Map.of(
                VerificationRunAction.VERIFICATION_RUN_ID, verificationRunId.value(),
                VerificationRunAction.CANDIDATE_ID, candidateId.value(),
                VerificationRunAction.EXPECTED_VERIFICATION_RUN_VERSION, expectedVerificationRunVersion);
    }

    private static String requireText(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return text.trim();
    }
}
