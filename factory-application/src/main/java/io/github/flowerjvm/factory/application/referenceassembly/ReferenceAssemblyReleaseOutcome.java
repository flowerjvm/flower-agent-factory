package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import java.util.Objects;

/** Exact released ledger snapshot and its canonical shipment manifest. */
public record ReferenceAssemblyReleaseOutcome(
        ReferenceAssembly referenceAssembly,
        CertificationArtifactLock releaseManifest,
        boolean committedNow) {
    public ReferenceAssemblyReleaseOutcome {
        Objects.requireNonNull(referenceAssembly, "referenceAssembly");
        Objects.requireNonNull(releaseManifest, "releaseManifest");
        if (referenceAssembly.status() != ReferenceAssemblyStatus.RELEASED
                || referenceAssembly.releaseManifest().filter(releaseManifest::equals).isEmpty()) {
            throw new IllegalArgumentException(
                    "release outcome must contain the exact RELEASED manifest lock");
        }
    }
}
