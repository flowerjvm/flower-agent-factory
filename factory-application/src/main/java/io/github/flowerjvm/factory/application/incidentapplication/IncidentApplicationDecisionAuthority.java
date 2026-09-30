package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.decision.*;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.ids.*;
import java.time.Clock;
import java.util.*;

/** Exact whole-product review; upstream Agent or Reference Assembly decisions grant no authority. */
public final class IncidentApplicationDecisionAuthority implements DecisionSubjectAuthority {
    public static final String DECISION_TYPE = "INCIDENT_APPLICATION_RELEASE_REVIEW";
    public static final String SUBJECT_TYPE = "INCIDENT_APPLICATION_RELEASE";
    public static final String OPTIONS_SCHEMA_ID = "factory.incident-application.release-review-options.v1";
    public static final String REQUIRED_PERMISSION = "factory.incident-application.release.approve";
    private final IncidentApplicationLedger ledger;
    private final Clock clock;
    public IncidentApplicationDecisionAuthority(IncidentApplicationLedger ledger, Clock clock) { this.ledger = Objects.requireNonNull(ledger); this.clock = Objects.requireNonNull(clock); }
    @Override public String decisionType() { return DECISION_TYPE; }
    @Override public java.time.Instant decisionDeadline(BuildSession session,DecisionPoint point) {
        var product=ledger.find(session.tenantId(),session.buildSessionId()).orElseThrow(IncidentApplicationOrder::invalid);
        IncidentApplicationActions.require(session.productLineId().equals(IncidentApplicationOrder.PRODUCT_LINE_ID)
                && Objects.equals(product.decisionPointId(),point.decisionPointId()) && session.deadlineAt().equals(product.order().deadlineAt()));
        return ledger.executionDeadline(product);
    }
    @Override public void validateCurrentSubject(TenantId tenant, BuildSession session, DecisionPoint point) {
        var product = ledger.find(tenant, session.buildSessionId()).orElseThrow(IncidentApplicationOrder::invalid);
        IncidentApplicationActions.require(session.productLineId().equals(IncidentApplicationOrder.PRODUCT_LINE_ID)
                && session.status() == BuildSessionStatus.WAITING_RELEASE_REVIEW && session.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW
                && product.status() == IncidentApplicationProduct.Status.REVIEW && product.decisionPointId().equals(point.decisionPointId())
                && product.releaseSubject().hash().equals(point.subjectHash()) && ledger.inspectedVersion(product,product.version()) == point.subjectVersion()
                && point.type().equals(DECISION_TYPE) && point.subjectType().equals(SUBJECT_TYPE) && point.subjectId().equals(session.buildSessionId().value())
                && point.optionsSchemaId().equals(OPTIONS_SCHEMA_ID) && point.requiredPermissions().equals(Set.of(REQUIRED_PERMISSION))
                && point.minimumApprovers() == 1 && point.policySnapshotRef().equals(product.workOrder().policy().reference())
                && point.dueAt().equals(ledger.executionDeadline(product)) && clock.instant().isBefore(point.dueAt()));
        ledger.requireCurrentComponent(product.order(), clock.instant());
    }
    public static Artifact subject(IncidentApplicationProduct product, long inspectedVersion) {
        var values = new LinkedHashMap<String,Object>();
        values.put("buildSessionId", product.order().buildSessionId().value()); values.put("variant", product.order().variant().name());
        values.put("inspectedVersion", inspectedVersion); values.put("workOrder", IncidentApplicationArtifacts.lockMap(product.workOrder().workOrder()));
        values.put("candidate", IncidentApplicationArtifacts.lockMap(product.product().candidate()));
        values.put("billOfMaterials", IncidentApplicationArtifacts.lockMap(product.product().billOfMaterials()));
        values.put("bundle", IncidentApplicationArtifacts.lockMap(product.product().bundle()));
        values.put("verification", IncidentApplicationArtifacts.lockMap(product.verification().report()));
        values.put("fixtureSuite", IncidentApplicationArtifacts.lockMap(product.verification().fixtureSuite()));
        values.put("policy", IncidentApplicationArtifacts.lockMap(product.workOrder().policy()));
        values.put("componentCertificationId", product.order().component().certificationId().value());
        values.put("componentCertification", IncidentApplicationArtifacts.lockMap(product.order().component().certificationManifest()));
        values.put("componentCandidateHash", product.order().component().candidateHash().sha256());
        return IncidentApplicationArtifacts.document(product.order().tenantId(), "release-subject", values);
    }
}
