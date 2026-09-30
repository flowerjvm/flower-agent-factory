package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate;
import io.github.flowerjvm.factory.application.decision.*;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.run.*;
import java.util.*;
import static io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationActions.require;
import static io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationArtifacts.*;

/** Read-only full gate: a RELEASED row alone is never an exported product or new consumption authority. */
public final class IncidentApplicationReleasedReadGate {
    private final IncidentApplicationLedger ledger; private final BuildSessionRepository sessions;
    private final DecisionPointRepository points; private final DecisionRepository decisions;
    private final ArtifactStore artifacts; private final RunStore runs;
    private final CertifiedAgentComponentReadGate components; private final IncidentApplicationProductionTool tool;
    public IncidentApplicationReleasedReadGate(IncidentApplicationLedger ledger,BuildSessionRepository sessions,
            DecisionPointRepository points,DecisionRepository decisions,ArtifactStore artifacts,RunStore runs,
            CertifiedAgentComponentReadGate components,IncidentApplicationProductionTool tool) {
        this.ledger=Objects.requireNonNull(ledger); this.sessions=Objects.requireNonNull(sessions); this.points=Objects.requireNonNull(points);
        this.decisions=Objects.requireNonNull(decisions); this.artifacts=Objects.requireNonNull(artifacts); this.runs=Objects.requireNonNull(runs);
        this.components=Objects.requireNonNull(components); this.tool=Objects.requireNonNull(tool);
    }
    public IncidentApplicationProduct resolve(TenantId tenant,BuildSessionId sessionId) {
        try {
            var product=ledger.find(tenant,sessionId).orElseThrow(IncidentApplicationOrder::invalid);
            require(product.status()==IncidentApplicationProduct.Status.RELEASED && product.order().tenantId().equals(tenant) && product.order().buildSessionId().equals(sessionId));
            var session=sessions.find(tenant,sessionId).orElseThrow(IncidentApplicationOrder::invalid);
            require(session.status()==BuildSessionStatus.SUCCEEDED && session.currentPhase()==BuildSessionPhase.COMPLETE
                    && session.productLineId().equals(IncidentApplicationOrder.PRODUCT_LINE_ID) && session.cancellationRequestedAt().isEmpty()
                    && session.projectId().equals(product.order().projectId()) && session.createdBy().equals(IncidentApplicationActions.OWNER)
                    && session.requirementsHash().equals(product.workOrder().requirements().hash()) && session.currentCertificationId().isEmpty());
            var intent=ledger.intent(product.activeOperationId()).orElseThrow(IncidentApplicationOrder::invalid);
            var owner=runs.find(product.releaseActionRunId()).orElseThrow(IncidentApplicationOrder::invalid);
            require(intent.stage()==IncidentApplicationIntent.Stage.RELEASE && intent.status()==IncidentApplicationIntent.Status.COMPLETED
                    && "INCIDENT_APPLICATION_OPERATION_COMPLETED".equals(intent.stableCode()) && product.version()==intent.subjectVersion()+2
                    && intent.tenantId().equals(tenant) && intent.buildSessionId().equals(sessionId) && intent.actionRunId().equals(owner.runId())
                    && owner.actionId().equals(IncidentApplicationActions.RELEASE) && owner.userId().equals(IncidentApplicationActions.OWNER)
                    && owner.requesterId().equals(IncidentApplicationActions.OWNER) && IncidentApplicationRunner.exactResult(product,intent,owner));
            var expectedInput=new LinkedHashMap<>(IncidentApplicationActions.stageInput(product,IncidentApplicationIntent.Stage.RELEASE)); expectedInput.put("expectedVersion",intent.subjectVersion());
            require(Arrays.equals(canonical(owner.input()),canonical(expectedInput))
                    && owner.duplicateKey().equals(IncidentApplicationActions.stageKey(product,IncidentApplicationIntent.Stage.RELEASE,intent.subjectVersion())));
            var point=points.find(tenant,product.decisionPointId()).orElseThrow(IncidentApplicationOrder::invalid);
            require(point.type().equals(IncidentApplicationDecisionAuthority.DECISION_TYPE) && point.subjectType().equals(IncidentApplicationDecisionAuthority.SUBJECT_TYPE)
                    && point.status()==DecisionPointStatus.APPROVED && point.version()==1 && point.subjectVersion()==ledger.inspectedVersion(product,intent.subjectVersion())
                    && point.dueAt().equals(ledger.executionDeadline(product)) && product.updatedAt().isBefore(point.dueAt())
                    && point.subjectId().equals(sessionId.value()) && point.buildSessionId().equals(sessionId) && point.subjectHash().equals(product.releaseSubject().hash())
                    && point.questionArtifactRef().equals(product.releaseSubject().reference()) && point.minimumApprovers()==1
                    && point.requiredPermissions().equals(Set.of(IncidentApplicationDecisionAuthority.REQUIRED_PERMISSION))
                    && point.optionsSchemaId().equals(IncidentApplicationDecisionAuthority.OPTIONS_SCHEMA_ID)
                    && point.policySnapshotRef().equals(product.workOrder().policy().reference()));
            var decision=decisions.find(tenant,point.terminalDecisionId().orElseThrow()).orElseThrow(IncidentApplicationOrder::invalid);
            IncidentApplicationReleaseDocuments.requireDecision(product,point,decision);
            var subject=IncidentApplicationDecisionAuthority.subject(product,point.subjectVersion());
            ledger.requirePriorStages(tenant,sessionId,IncidentApplicationIntent.Stage.RELEASE);
            var build=ledger.stageIntent(tenant,sessionId,IncidentApplicationIntent.Stage.BUILD).orElseThrow(IncidentApplicationOrder::invalid);
            var verification=ledger.stageIntent(tenant,sessionId,IncidentApplicationIntent.Stage.VERIFY).orElseThrow(IncidentApplicationOrder::invalid);
            var renewal=ledger.reviewRenewal(tenant,sessionId);
            var certificate=IncidentApplicationReleaseDocuments.certificate(product,intent,point,build,verification,renewal);
            var release=IncidentApplicationReleaseDocuments.release(product,intent,certificate);
            require(lock(subject).equals(product.releaseSubject()) && lock(certificate).equals(product.productCertification()) && lock(release).equals(product.releaseManifest()));
            for(var artifact:List.of(subject,certificate,release)) {
                var actual=IncidentApplicationArtifacts.require(artifacts,tenant,lock(artifact));
                require(actual.mediaType().equals(artifact.mediaType()) && Arrays.equals(actual.content(),artifact.content()));
            }
            renewal.ifPresent(value -> IncidentApplicationArtifacts.require(artifacts,tenant,lock(value.evidence())));
            tool.validate(product.workOrder(),product.product(),product.verification());
            require(components.resolve(tenant,product.order().component()).reference().equals(product.order().component()));
            require(ledger.find(tenant,sessionId).filter(product::equals).isPresent()
                    && sessions.find(tenant,sessionId).filter(session::equals).isPresent()
                    && ledger.intent(intent.operationId()).filter(intent::equals).isPresent() && runs.find(owner.runId()).filter(owner::equals).isPresent()
                    && ledger.intent(build.operationId()).filter(build::equals).isPresent() && ledger.intent(verification.operationId()).filter(verification::equals).isPresent());
            ledger.requirePriorStages(tenant,sessionId,IncidentApplicationIntent.Stage.RELEASE);
            return product;
        } catch(RuntimeException invalid) { throw new IllegalArgumentException("INCIDENT_APPLICATION_RELEASE_NOT_ELIGIBLE"); }
    }
}
