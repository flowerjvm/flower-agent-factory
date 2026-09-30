package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import java.util.Objects;

/** Exact artifact locks returned by assembly; never proof that the complete application passed. */
public record IncidentApplicationPreparedProduct(CertificationArtifactLock candidate,
        CertificationArtifactLock billOfMaterials, CertificationArtifactLock bundle,
        CertificationArtifactLock buildEvidence) {
    public IncidentApplicationPreparedProduct {
        Objects.requireNonNull(candidate); Objects.requireNonNull(billOfMaterials);
        Objects.requireNonNull(bundle); Objects.requireNonNull(buildEvidence);
    }
}
