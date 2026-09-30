package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import java.time.Instant;
import java.util.Objects;

/** Line-owned production truth. Upstream Agent certification is an input, never this product's certificate. */
public record IncidentApplicationProduct(IncidentApplicationBuildWorkOrder workOrder, Status status,
        IncidentApplicationPreparedProduct product, IncidentApplicationWholeVerification verification,
        DecisionPointId decisionPointId, CertificationArtifactLock releaseSubject,
        CertificationArtifactLock productCertification, CertificationArtifactLock releaseManifest,
        String activeOperationId, String releaseActionRunId, String stableCode,
        long version, Instant createdAt, Instant updatedAt) {
    public enum Status { ACCEPTED, BUILDING, BUILT, VERIFYING, REVIEW, RELEASING, RELEASED, FAILED, MANUAL_REVIEW }
    public IncidentApplicationProduct {
        Objects.requireNonNull(workOrder); Objects.requireNonNull(status); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        if (version < 0 || updatedAt.isBefore(createdAt)) throw IncidentApplicationOrder.invalid();
        if ((decisionPointId == null) != (releaseSubject == null)) throw IncidentApplicationOrder.invalid();
        if ((productCertification == null) != (releaseManifest == null)) throw IncidentApplicationOrder.invalid();
        if (status == Status.RELEASED && (productCertification == null || releaseActionRunId == null)) throw IncidentApplicationOrder.invalid();
        if ((status == Status.REVIEW || status == Status.RELEASING || status == Status.RELEASED)
                && (product == null || verification == null || !verification.passed() || decisionPointId == null)) throw IncidentApplicationOrder.invalid();
        if (productCertification != null && status != Status.RELEASED) throw IncidentApplicationOrder.invalid();
    }
    public static IncidentApplicationProduct accepted(IncidentApplicationBuildWorkOrder workOrder, Instant now) {
        return new IncidentApplicationProduct(workOrder, Status.ACCEPTED, null, null, null, null, null, null, null, null, null, 0, now, now);
    }
    public IncidentApplicationOrder order() { return workOrder.order(); }
    public boolean terminal() { return status == Status.RELEASED || status == Status.FAILED || status == Status.MANUAL_REVIEW; }
    public IncidentApplicationProduct next(Status status, IncidentApplicationPreparedProduct product,
            IncidentApplicationWholeVerification verification, DecisionPointId decisionPointId,
            CertificationArtifactLock releaseSubject, CertificationArtifactLock certification,
            CertificationArtifactLock release, String operationId, String releaseAction, String code, Instant now) {
        if (terminal() || now.isBefore(updatedAt)) throw IncidentApplicationOrder.invalid();
        return new IncidentApplicationProduct(workOrder, status, product, verification, decisionPointId, releaseSubject,
                certification, release, operationId, releaseAction, code, version + 1, createdAt, now);
    }
}
