package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.application.build.BuildSession;

/**
 * Atomic boundary for creating a REQUESTED Certification and advancing its BuildSession.
 *
 * <p>The implementation must lock or CAS the exact {@code expectedSession}, insert the immutable
 * {@code requestedCertification}, and persist {@code certifyingSession} in one database
 * transaction. A retry must return {@link CertificationRequestDisposition#EXISTING_EXACT} only
 * when the stored Certification has the same immutable input lock/id and the stored BuildSession
 * is already the exact CERTIFYING/CERTIFY candidate authority. It must never leave only one of the
 * two rows committed.
 */
@FunctionalInterface
public interface CertificationRequestTransaction {
    AgentPackCertificationRequestOutcome ensureRequested(
            BuildSession expectedSession,
            BuildSession certifyingSession,
            Certification requestedCertification);
}
