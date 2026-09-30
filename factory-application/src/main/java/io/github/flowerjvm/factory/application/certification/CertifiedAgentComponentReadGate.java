package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.ids.TenantId;

/** AGENT_PACK-specific strict read boundary for downstream product assembly. */
@FunctionalInterface
public interface CertifiedAgentComponentReadGate {
    ResolvedCertifiedAgentComponent resolve(
            TenantId trustedTenantId, CertifiedAgentComponentRef reference);
}
