package io.github.flowerjvm.factory.application.verification;

import java.util.Objects;

/** Establishes whether a VerificationRun is owned by one canonical terminal Action attempt. */
@FunctionalInterface
public interface VerificationActionEvidenceOwner {
    Assessment assess(VerificationRun verificationRun);

    enum Status {
        PENDING,
        CANONICAL_SUCCEEDED,
        ORPHANED,
        INVALID_TERMINAL
    }

    record Assessment(Status status, String code) {
        public Assessment {
            Objects.requireNonNull(status, "status");
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("code must not be blank");
            }
        }

        public static Assessment pending() {
            return new Assessment(Status.PENDING, "VERIFICATION_ACTION_PENDING");
        }

        public static Assessment canonical() {
            return new Assessment(Status.CANONICAL_SUCCEEDED, "VERIFICATION_ACTION_CANONICAL");
        }
    }
}
