package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;

/** Strict tenant-authoritative read boundary for one released Reference Assembly product. */
@FunctionalInterface
public interface ReleasedReferenceAssemblyReadGate {
    ResolvedReleasedReferenceAssembly resolve(
            TenantId trustedTenantId, ReferenceAssemblyId referenceAssemblyId);
}
