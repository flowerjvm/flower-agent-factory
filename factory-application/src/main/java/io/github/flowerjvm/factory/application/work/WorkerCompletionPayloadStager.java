package io.github.flowerjvm.factory.application.work;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import java.time.Instant;

/**
 * Canonicalizes a tokenless polled completion into the same durable inbox used by callbacks.
 *
 * <p>{@code trustedReceivedAt} is host authority: either the current trusted clock while still
 * before the cutoff, or a validated terminal-commit time promoted from the authenticated durable
 * worker operation journal. Implementations must preserve it as the inbox receipt time; they must
 * not replace it with processing/restart time or a provider-supplied completion timestamp.
 */
@FunctionalInterface
public interface WorkerCompletionPayloadStager {
    WorkerCallbackReceipt stage(
            TenantId tenantId,
            String workerBindingId,
            CodingWorkerCompletionPayload payload,
            Instant trustedReceivedAt);
}
