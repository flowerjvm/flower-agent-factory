package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.util.Optional;

/** Tenant-scoped version-CAS repository for exact certification locks. */
public interface CertificationRepository {
    /** Creates only the initial REQUESTED snapshot; terminal state requires CAS. */
    void create(Certification certification);

    Optional<Certification> find(TenantId tenantId, CertificationId certificationId);

    /**
     * Returns the newest Certification whose immutable subject is the exact current candidate.
     *
     * <p>Implementations must scope by all four arguments and use a deterministic tie-breaker;
     * they must not substitute a tenant-wide, session-wide, candidate-id-only or floating/latest
     * lookup. The default preserves source compatibility while failing closed until persistence
     * explicitly implements this continuation query.
     */
    default Optional<Certification> findLatestForCandidate(
            TenantId tenantId,
            BuildSessionId buildSessionId,
            CandidateId candidateId,
            ContentHash candidateHash) {
        throw new UnsupportedOperationException(
                "exact BuildSession/candidate Certification query is not implemented");
    }

    boolean compareAndSet(Certification expected, Certification next);
}
