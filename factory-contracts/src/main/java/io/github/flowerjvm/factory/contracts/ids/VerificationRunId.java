package io.github.flowerjvm.factory.contracts.ids;

/** Identity of one deterministic verification execution. */
public record VerificationRunId(String value) {
    public VerificationRunId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("verificationRunId must not be blank");
        }
    }
}
