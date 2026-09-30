package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.certification.ResolvedCertifiedAgentComponent;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyInspectionReport;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseSubject;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import java.util.Objects;

/** Exact released product plus the canonical graph and currently valid upstream component. */
public record ResolvedReleasedReferenceAssembly(
        ReferenceAssembly referenceAssembly,
        CertificationArtifactLock releaseManifestArtifact,
        ReferenceAssemblyReleaseManifest releaseManifest,
        ReferenceAssemblyRequirement requirement,
        ReferenceAssemblyManifest assemblyManifest,
        ReferenceAssemblyInspectionReport inspectionReport,
        ReferenceAssemblyReleaseSubject releaseSubject,
        ResolvedCertifiedAgentComponent component) {

    public ResolvedReleasedReferenceAssembly {
        Objects.requireNonNull(referenceAssembly, "referenceAssembly");
        Objects.requireNonNull(releaseManifestArtifact, "releaseManifestArtifact");
        Objects.requireNonNull(releaseManifest, "releaseManifest");
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(assemblyManifest, "assemblyManifest");
        Objects.requireNonNull(inspectionReport, "inspectionReport");
        Objects.requireNonNull(releaseSubject, "releaseSubject");
        Objects.requireNonNull(component, "component");
        if (referenceAssembly.status() != ReferenceAssemblyStatus.RELEASED
                || referenceAssembly.releaseManifest()
                        .filter(releaseManifestArtifact::equals)
                        .isEmpty()
                || !releaseManifest.referenceAssemblyId()
                        .equals(referenceAssembly.referenceAssemblyId())
                || !releaseManifest.component().equals(component.reference())) {
            throw new IllegalArgumentException(
                    "resolved released Reference Assembly must contain one exact product graph");
        }
    }
}
