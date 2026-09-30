package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import java.util.Objects;

/** Canonical ledger observation returned by the certification request transaction. */
public record AgentPackCertificationRequestOutcome(
        CertificationRequestDisposition disposition,
        Certification certification,
        BuildSession buildSession) {

    public AgentPackCertificationRequestOutcome {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(certification, "certification");
        Objects.requireNonNull(buildSession, "buildSession");
        if (!certification.inputLock().tenantId().equals(buildSession.tenantId())
                || !certification.inputLock().buildSessionId().equals(buildSession.buildSessionId())) {
            throw new IllegalArgumentException(
                    "Certification and BuildSession must share the trusted tenant/build identity");
        }
        if (disposition != CertificationRequestDisposition.CONFLICT
                && (certification.status() != CertificationStatus.REQUESTED
                        || buildSession.status() != BuildSessionStatus.CERTIFYING
                        || buildSession.currentPhase() != BuildSessionPhase.CERTIFY
                        || buildSession.currentCandidateId()
                                .filter(certification.inputLock().candidateId()::equals)
                                .isEmpty()
                        || buildSession.currentCandidateHash()
                                .filter(certification.inputLock().candidateHash()::equals)
                                .isEmpty())) {
            throw new IllegalArgumentException(
                    "successful certification request outcome must expose the exact REQUESTED/CERTIFYING pair");
        }
    }
}
