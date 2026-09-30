package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.decision.DecisionPoint;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseSubject;
import java.util.Objects;

/** Exact ledger and artifact observation after release-review creation or reconciliation. */
public record ReferenceAssemblyReleaseReviewResult(
        Disposition disposition,
        ReferenceAssembly referenceAssembly,
        DecisionPoint decisionPoint,
        ReferenceAssemblyReleaseSubject releaseSubject,
        CertificationArtifactLock releaseSubjectArtifact) {

    public ReferenceAssemblyReleaseReviewResult {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(referenceAssembly, "referenceAssembly");
        Objects.requireNonNull(decisionPoint, "decisionPoint");
        Objects.requireNonNull(releaseSubject, "releaseSubject");
        Objects.requireNonNull(releaseSubjectArtifact, "releaseSubjectArtifact");
        if (referenceAssembly.status() != ReferenceAssemblyStatus.INSPECTED
                || referenceAssembly.releaseDecisionPointId()
                        .filter(decisionPoint.decisionPointId()::equals)
                        .isEmpty()
                || referenceAssembly.releaseSubjectHash()
                        .filter(releaseSubjectArtifact.hash()::equals)
                        .isEmpty()
                || !decisionPoint.subjectHash().equals(releaseSubjectArtifact.hash())
                || !decisionPoint.questionArtifactRef().equals(releaseSubjectArtifact.reference())
                || !releaseSubject.referenceAssemblyId()
                        .equals(referenceAssembly.referenceAssemblyId())) {
            throw new IllegalArgumentException(
                    "release-review result must contain one exact bound observation");
        }
    }

    public enum Disposition {
        CREATED_AND_BOUND,
        RECOVERED_AND_BOUND,
        EXISTING_EXACT
    }
}
