package io.github.flowerjvm.factory.infrastructure.verification;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;

/** Narrow seam for the immutable host toolchain installer; package-private for verifier tests. */
interface TrustedToolchainInstaller {
    InstalledToolchain descriptor();

    void install(TenantId tenantId);

    record InstalledToolchain(ArtifactReference reference, ContentHash hash) {}
}
