package io.github.flowerjvm.factory.application.candidate;

import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import java.util.Optional;

/** Insert-only, tenant-scoped repository SPI for immutable candidate snapshots. */
public interface CandidateVersionRepository {
    void create(CandidateVersion candidateVersion);

    Optional<CandidateVersion> find(TenantId tenantId, CandidateId candidateId);

    Optional<CandidateVersion> findByBuildSessionAndWorkOrder(
            TenantId tenantId,
            BuildSessionId buildSessionId,
            WorkOrderId createdByWorkOrderId);
}
