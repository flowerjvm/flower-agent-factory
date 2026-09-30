package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.util.List;
import java.util.Optional;

/** Tenant-scoped, version-CAS repository for the concrete Reference Assembly ledger. */
public interface ReferenceAssemblyRepository {
    /** Creates only the immutable version-zero REQUESTED snapshot. */
    void create(ReferenceAssembly assembly);

    Optional<ReferenceAssembly> find(TenantId tenantId, ReferenceAssemblyId referenceAssemblyId);

    Optional<ReferenceAssembly> findByBuildSession(
            TenantId tenantId, BuildSessionId buildSessionId);

    /** Returns every released assembly that consumed the exact component Certification identity. */
    List<ReferenceAssembly> findReleasedByComponentCertification(
            TenantId tenantId, CertificationId componentCertificationId);

    /**
     * Bounded, stable-order reverse provenance lookup for products released with one exact
     * component Certification.
     */
    default List<ReferenceAssembly> findReleasedByComponentCertification(
            TenantId tenantId, CertificationId componentCertificationId, int limit) {
        throw new UnsupportedOperationException(
                "bounded released-component provenance query is not implemented by this repository");
    }

    /**
     * Applies an ordinary lifecycle transition with an exact version CAS.
     *
     * <p>This general repository path must reject a {@code RELEASED} target. Release is a
     * controlled transaction boundary that must atomically revalidate its approval and Action
     * ownership before persisting the terminal snapshot.
     */
    boolean compareAndSet(ReferenceAssembly expected, ReferenceAssembly next);
}
