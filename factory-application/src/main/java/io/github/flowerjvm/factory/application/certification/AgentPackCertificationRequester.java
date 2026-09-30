package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;

/** Trusted request-construction boundary used by the Agent Pack certification continuation. */
@FunctionalInterface
public interface AgentPackCertificationRequester {
    AgentPackCertificationRequestOutcome ensureRequested(
            TenantId trustedTenantId, BuildSessionId buildSessionId);
}
