package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.application.decision.*;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import java.util.*;
import static io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationArtifacts.*;
import static io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationActions.require;

/** One canonical authority-document definition for final commit and independent released readback. */
public final class IncidentApplicationReleaseDocuments {
    private IncidentApplicationReleaseDocuments() { }
    public static void requireDecision(IncidentApplicationProduct product,DecisionPoint point,Decision decision) {
        require(decision.tenantId().equals(product.order().tenantId()) && decision.decisionPointId().equals(point.decisionPointId())
                && decision.decisionId().equals(point.terminalDecisionId().orElseThrow()) && decision.decision()==DecisionOutcome.APPROVE
                && decision.subjectHash().equals(product.releaseSubject().hash()) && decision.createdAt().equals(point.decidedAt().orElseThrow())
                && !decision.createdAt().isBefore(point.openedAt()) && decision.createdAt().isBefore(point.dueAt())
                && !decision.decidedBy().isBlank() && !decision.decidedBy().equals(IncidentApplicationActions.OWNER)
                && decision.selectedOption().isEmpty() && !decision.deciderAuthoritySnapshotRef().value().isBlank());
    }
    public static Artifact certificate(IncidentApplicationProduct current,IncidentApplicationIntent intent,DecisionPoint decision,
            IncidentApplicationIntent build,IncidentApplicationIntent verification) {
        return certificate(current,intent,decision,build,verification,Optional.empty());
    }
    public static Artifact certificate(IncidentApplicationProduct current,IncidentApplicationIntent intent,DecisionPoint decision,
            IncidentApplicationIntent build,IncidentApplicationIntent verification,Optional<IncidentApplicationReviewRenewal> renewal) {
        var fields=new LinkedHashMap<String,Object>(); fields.put("productLineId",IncidentApplicationOrder.PRODUCT_LINE_ID.value());
        fields.put("certificationId","cert-incident-"+hash(intent.operationId())); fields.put("profile","local-demo");
        fields.put("buildSessionId",current.order().buildSessionId().value()); fields.put("variant",current.order().variant().name());
        fields.put("candidate",lockMap(current.product().candidate())); fields.put("billOfMaterials",lockMap(current.product().billOfMaterials()));
        fields.put("bundle",lockMap(current.product().bundle())); fields.put("verification",lockMap(current.verification().report()));
        fields.put("fixtureSuite",lockMap(current.verification().fixtureSuite())); fields.put("releaseSubject",lockMap(current.releaseSubject()));
        fields.put("workOrder",lockMap(current.workOrder().workOrder())); fields.put("policy",lockMap(current.workOrder().policy()));
        fields.put("componentCertificationId",current.order().component().certificationId().value());
        fields.put("componentCertification",lockMap(current.order().component().certificationManifest()));
        fields.put("componentCandidateHash",current.order().component().candidateHash().sha256());
        fields.put("decisionPointId",decision.decisionPointId().value()); fields.put("decisionId",decision.terminalDecisionId().orElseThrow().value());
        fields.put("actionRunId",intent.actionRunId()); fields.put("issuedAt",intent.createdAt().toString());
        fields.put("buildOperationId",build.operationId()); fields.put("buildActionRunId",build.actionRunId());
        fields.put("verificationOperationId",verification.operationId()); fields.put("verificationActionRunId",verification.actionRunId());
        renewal.ifPresent(value -> fields.put("reviewRenewal",lockMap(lock(value.evidence()))));
        return document(current.order().tenantId(),"product-certification",fields);
    }
    public static Artifact release(IncidentApplicationProduct current,IncidentApplicationIntent intent,Artifact certificate) {
        return document(current.order().tenantId(),"release",Map.of("buildSessionId",current.order().buildSessionId().value(),
                "productCertification",lockMap(lock(certificate)),"bundle",lockMap(current.product().bundle()),
                "billOfMaterials",lockMap(current.product().billOfMaterials()),"releaseSubject",lockMap(current.releaseSubject()),
                "actionRunId",intent.actionRunId(),"operationId",intent.operationId(),"boundary","factory-handoff-only"));
    }
}
