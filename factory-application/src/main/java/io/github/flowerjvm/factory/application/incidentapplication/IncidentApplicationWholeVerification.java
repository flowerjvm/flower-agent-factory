package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import java.util.Objects;

/** Factory-owned complete-product evidence, separate from upstream component verification. */
public record IncidentApplicationWholeVerification(boolean passed, String stableCode,
        CertificationArtifactLock report, CertificationArtifactLock fixtureSuite) {
    public IncidentApplicationWholeVerification {
        stableCode = IncidentApplicationOrder.text(stableCode, 128);
        if (!stableCode.matches("[A-Z][A-Z0-9_]*")) throw IncidentApplicationOrder.invalid();
        Objects.requireNonNull(report); Objects.requireNonNull(fixtureSuite);
    }
}
