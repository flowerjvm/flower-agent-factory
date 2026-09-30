package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.application.action.CertificationIssueInput;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.Optional;

/** Atomic exact-state validation and durable PENDING-intent creation for the Certification Action. */
public interface CertificationDispatchTransaction {
    CertificationDispatchIntent prepare(
            TenantId tenantId,
            CertificationIssueInput input,
            String actionRunId,
            String attemptTokenHash,
            Instant preparedAt);

    Optional<CertificationDispatchIntent> findExact(
            TenantId tenantId,
            CertificationIssueInput input,
            String actionRunId,
            String attemptTokenHash);
}
