package io.github.flowerjvm.factory.application.certification;

import java.util.Objects;

/** Establishes whether one CERTIFIED ledger row is owned by a canonical issuance Action. */
@FunctionalInterface
public interface CertificationActionEvidenceOwner {
    Assessment assess(Certification certification);

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
            return new Assessment(Status.PENDING, "CERTIFICATION_ACTION_PENDING");
        }

        public static Assessment canonical() {
            return new Assessment(Status.CANONICAL_SUCCEEDED, "CERTIFICATION_ACTION_CANONICAL");
        }
    }
}
