package io.github.flowerjvm.factory.application.certification;

import java.util.Objects;

/**
 * Final atomic commit boundary for Certification issuance.
 *
 * <p>The implementation must lock the Certification before its owning BuildSession, then either
 * return an exact prior commit or revalidate the current uncancelled certification phase before
 * persisting {@code proposed}. Artifact staging deliberately happens before this transaction.
 */
@FunctionalInterface
public interface CertificationIssuanceTransaction {
    CertificationIssuanceCommit commit(
            CertificationDispatchIntent intent,
            Certification expected,
            Certification proposed);

    /** Canonical domain result and whether this invocation won the durable transition. */
    record CertificationIssuanceCommit(Certification certification, boolean committedNow) {
        public CertificationIssuanceCommit {
            Objects.requireNonNull(certification, "certification");
        }
    }
}
