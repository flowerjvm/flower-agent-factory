package io.github.flowerjvm.factory.application.verification;

import java.util.Optional;

/** Resolves the Action Runtime duplicate owner retained for one exact verification key. */
@FunctionalInterface
public interface VerificationActionDuplicateOwnerLookup {
    Optional<String> findOwnerRunId(String tenantId, String actionId, String idempotencyKey);
}
