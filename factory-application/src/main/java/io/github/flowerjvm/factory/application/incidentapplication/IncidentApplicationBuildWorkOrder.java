package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import java.util.Objects;

/** Code-owned deterministic production instruction, not a mutable builder workspace. */
public record IncidentApplicationBuildWorkOrder(IncidentApplicationOrder order,
        CertificationArtifactLock requirements, CertificationArtifactLock blueprint,
        CertificationArtifactLock moduleCatalog, CertificationArtifactLock policy,
        CertificationArtifactLock workOrder, String algorithmId) {
    public IncidentApplicationBuildWorkOrder {
        Objects.requireNonNull(order); Objects.requireNonNull(requirements); Objects.requireNonNull(blueprint);
        Objects.requireNonNull(moduleCatalog); Objects.requireNonNull(policy); Objects.requireNonNull(workOrder);
        algorithmId = IncidentApplicationOrder.text(algorithmId, 128);
    }
}
