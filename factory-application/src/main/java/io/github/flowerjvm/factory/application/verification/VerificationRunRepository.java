package io.github.flowerjvm.factory.application.verification;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import java.util.Optional;

/** Version-CAS, tenant-scoped repository SPI for deterministic verification executions. */
public interface VerificationRunRepository {
    void create(VerificationRun verificationRun);

    Optional<VerificationRun> find(TenantId tenantId, VerificationRunId verificationRunId);

    Optional<VerificationRun> findLatestForCandidate(
            TenantId tenantId,
            BuildSessionId buildSessionId,
            CandidateId candidateId,
            ContentHash candidateHash,
            String gateProfile);

    /** Latest scripted PR3 result for the exact immutable candidate, independent of profile display text. */
    default Optional<VerificationRun> findLatestForCandidate(
            TenantId tenantId,
            BuildSessionId buildSessionId,
            CandidateId candidateId,
            ContentHash candidateHash) {
        throw new UnsupportedOperationException("candidate query is not implemented by this repository");
    }

    boolean compareAndSet(VerificationRun expected, VerificationRun next);
}
