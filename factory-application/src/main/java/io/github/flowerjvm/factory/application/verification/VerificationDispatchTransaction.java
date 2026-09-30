package io.github.flowerjvm.factory.application.verification;

import io.github.flowerjvm.factory.application.action.VerificationRunInput;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Instant;
import java.util.Optional;

/** Atomic exact-state validation and durable intent creation for the verification Action. */
public interface VerificationDispatchTransaction {
    VerificationDispatchIntent prepare(
            TenantId tenantId,
            VerificationRunInput input,
            String actionRunId,
            String attemptTokenHash,
            Instant preparedAt);

    Optional<VerificationDispatchIntent> findExact(
            TenantId tenantId,
            VerificationRunInput input,
            String actionRunId,
            String attemptTokenHash);
}
