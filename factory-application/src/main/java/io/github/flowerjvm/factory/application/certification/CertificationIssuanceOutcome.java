package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import java.util.Objects;

/** Exact terminal Certification and its two canonical issued artifacts. */
public record CertificationIssuanceOutcome(
        Certification certification,
        CertificationArtifactLock certificationEvidence,
        CertificationArtifactLock certificationManifest,
        boolean issuedNow) {

    public CertificationIssuanceOutcome {
        Objects.requireNonNull(certification, "certification");
        Objects.requireNonNull(certificationEvidence, "certificationEvidence");
        Objects.requireNonNull(certificationManifest, "certificationManifest");
        if (certification.status() != CertificationStatus.CERTIFIED
                || certification.certificationEvidence().filter(certificationEvidence::equals).isEmpty()
                || certification.certificationManifest().filter(certificationManifest::equals).isEmpty()) {
            throw new IllegalArgumentException("outcome must match one exact CERTIFIED Certification");
        }
    }
}
