package io.github.flowerjvm.factory.application.verification;

import java.util.Objects;

/** Canonical durable result observed after a verifier execution or terminal retry. */
public record VerificationExecutionOutcome(VerificationRun verificationRun, boolean executedNow) {
    public VerificationExecutionOutcome {
        Objects.requireNonNull(verificationRun, "verificationRun");
        if (!verificationRun.status().isTerminal()) {
            throw new IllegalArgumentException("verification execution outcome must be terminal");
        }
    }
}
