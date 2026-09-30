package io.github.flowerjvm.factory.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.certification.*;
import io.github.flowerjvm.factory.application.decision.*;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.run.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;
import static io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationActions.require;
import static io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationArtifacts.*;

/**
 * Same-connection version-CAS ledger. Lock order is product, BuildSession, Decision, Certification,
 * ActionRun, intent. Release fences session and Action versions before publishing terminal product
 * truth. A stage lease is never stolen; uncertain execution is quarantined without a second build.
 */
public final class JdbcIncidentApplicationLedger implements IncidentApplicationLedger {
    private final DataSource source;
    private final ObjectMapper json;
    private final JdbcBuildSessionRepository sessions;
    private final JdbcDecisionPointRepository decisions;
    private final JdbcCertificationRepository certifications;
    private final JdbcArtifactStore artifacts;
    private final RunStore runs;
    private final CertifiedAgentComponentReadGate fullComponents;
    private final IncidentApplicationProductionTool tool;
    private final Clock clock;
    public JdbcIncidentApplicationLedger(DataSource source, ObjectMapper mapper, RunStore runs,
            CertifiedAgentComponentReadGate fullComponents, IncidentApplicationProductionTool tool, Clock clock) {
        this.source=Objects.requireNonNull(source); this.json=Objects.requireNonNull(mapper).copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.sessions=new JdbcBuildSessionRepository(source); this.decisions=new JdbcDecisionPointRepository(source,mapper);
        this.certifications=new JdbcCertificationRepository(source); this.artifacts=new JdbcArtifactStore(source,clock);
        this.runs=Objects.requireNonNull(runs); this.fullComponents=Objects.requireNonNull(fullComponents);
        this.tool=Objects.requireNonNull(tool); this.clock=Objects.requireNonNull(clock);
    }
    @Override public Optional<IncidentApplicationProduct> find(TenantId tenant, BuildSessionId session) {
        return connection(c->find(c,tenant,session,false));
    }
    @Override public Optional<IncidentApplicationIntent> intent(String operation) { return connection(c->intent(c,operation,false)); }
    @Override public Optional<IncidentApplicationReviewRenewal> reviewRenewal(TenantId tenant,BuildSessionId id) {
        return connection(c->verifiedRenewal(c,required(c,tenant,id,false)));
    }
    @Override public void requireRenewal(TenantId tenant,BuildSessionId id,Map<String,Object> input,boolean executing) {
        connection(c->{ var product=required(c,tenant,id,false); var session=session(c,product,false);
            require(product.order().projectId().value().equals(input.get("projectId")));
            currentComponent(c,product.order(),now(),false);
            var existing=rawRenewal(c,tenant,id);
            if(existing.isPresent()) {
                require(!executing);
                var renewal=verifiedRenewal(c,product).orElseThrow(IncidentApplicationOrder::invalid);
                require(renewal.requestKey().equals(IncidentApplicationReviewRenewalAction.key(tenant,input)));
            } else requireRenewable(c,product,session,input,now());
            return null;
        });
    }
    @Override public IncidentApplicationReviewRenewal renewReview(TenantId tenant,BuildSessionId id,
            Map<String,Object> input,ActionRun owner,Instant observedAt) {
        return transaction(c->{ var product=required(c,tenant,id,true); var session=session(c,product,true);
            require(rawRenewal(c,tenant,id).isEmpty());
            var old=decisions.find(c,tenant,product.decisionPointId(),true).orElseThrow(IncidentApplicationOrder::invalid);
            currentComponent(c,product.order(),now(),true);
            var locked=lockAction(c,owner.runId()); require(locked.equals(owner) && owner.status()==ActionRunStatus.RUNNING);
            requireRenewable(c,product,session,input,now());
            full(product.order()); tool.validate(product.workOrder(),product.product(),product.verification());
            for(var item:List.of(product.workOrder().requirements(),product.workOrder().blueprint(),product.workOrder().moduleCatalog(),
                    product.workOrder().policy(),product.workOrder().workOrder(),product.releaseSubject())) artifact(c,tenant,item);
            Instant now=now(); requireRenewable(c,product,session,input,now); currentComponent(c,product.order(),now,false);
            String key=IncidentApplicationReviewRenewalAction.key(tenant,input);
            var pointId=new DecisionPointId("incident-renewed-review-"+hash(tenant.value()+"\n"+id.value()+"\n"+key));
            var renewal=new IncidentApplicationReviewRenewal(tenant,id,old.decisionPointId(),pointId,product.releaseSubject(),
                    old.subjectVersion(),product.version()+1,product.order().deadlineAt(),now,
                    Instant.parse((String)input.get("deadlineAt")),owner.runId(),hash(owner.attemptToken()),key);
            IncidentApplicationReviewRenewalAction.requireOwner(owner,product,renewal);
            var point=new DecisionPoint(pointId,tenant,id,old.type(),DecisionPointStatus.OPEN,old.subjectType(),old.subjectId(),
                    old.subjectVersion(),old.subjectHash(),old.questionArtifactRef(),old.optionsSchemaId(),old.requiredPermissions(),
                    old.minimumApprovers(),old.policySnapshotRef(),now,renewal.deadlineAt(),Optional.empty(),Optional.empty(),0);
            decisions.create(c,point); var evidence=renewal.evidence(); artifacts.store(c,evidence);
            var updated=product.next(IncidentApplicationProduct.Status.REVIEW,product.product(),product.verification(),pointId,
                    product.releaseSubject(),null,null,product.activeOperationId(),null,null,now);
            casProduct(c,product,updated);
            // Session CAS fences concurrent Decision/cancellation without modifying its original deadline.
            project(c,session,BuildSessionStatus.WAITING_RELEASE_REVIEW,BuildSessionPhase.HUMAN_RELEASE_REVIEW,null,now);
            try(var insert=c.prepareStatement("INSERT INTO factory_incident_review_renewal (tenant_id,build_session_id,previous_decision_point_id,decision_point_id,action_run_id,renewal_json,renewal_hash,evidence_ref,evidence_hash) VALUES (?,?,?,?,?,?,?,?,?)")) {
                String encoded=encode(renewal); insert.setString(1,tenant.value()); insert.setString(2,id.value());
                insert.setString(3,old.decisionPointId().value()); insert.setString(4,pointId.value()); insert.setString(5,owner.runId());
                insert.setString(6,encoded); insert.setString(7,hash(encoded)); insert.setString(8,evidence.reference().value()); insert.setString(9,evidence.contentHash().sha256());
                require(insert.executeUpdate()==1);
            }
            return renewal;
        });
    }
    private void requireRenewable(Connection c,IncidentApplicationProduct product,BuildSession session,Map<String,Object> input,Instant now) throws SQLException {
        IncidentApplicationReviewRenewalAction.shape(input);
        require(product.order().buildSessionId().value().equals(input.get("buildSessionId"))
                && product.order().projectId().value().equals(input.get("projectId"))
                && product.status()==IncidentApplicationProduct.Status.REVIEW && product.productCertification()==null && product.releaseActionRunId()==null
                && session.status()==BuildSessionStatus.WAITING_RELEASE_REVIEW && session.currentPhase()==BuildSessionPhase.HUMAN_RELEASE_REVIEW
                && session.cancellationRequestedAt().isEmpty() && !now.isBefore(session.updatedAt()) && !now.isBefore(product.updatedAt())
                && product.version()==IncidentApplicationActions.version(input.get("expectedVersion"))
                && product.decisionPointId().value().equals(input.get("previousDecisionPointId"))
                && product.releaseSubject().hash().sha256().equals(input.get("subjectHash")) && !now.isBefore(product.order().deadlineAt()));
        Instant due=Instant.parse((String)input.get("deadlineAt"));
        require(now.isBefore(due) && !due.isAfter(now.plus(Duration.ofHours(48))));
        var old=decisions.find(c,product.order().tenantId(),product.decisionPointId(),false).orElseThrow(IncidentApplicationOrder::invalid);
        require(old.status()==DecisionPointStatus.OPEN && old.version()==0 && old.subjectVersion()==product.version()
                && old.dueAt().equals(product.order().deadlineAt()) && old.subjectHash().equals(product.releaseSubject().hash())
                && old.buildSessionId().equals(product.order().buildSessionId()) && old.type().equals(IncidentApplicationDecisionAuthority.DECISION_TYPE)
                && old.subjectType().equals(IncidentApplicationDecisionAuthority.SUBJECT_TYPE) && old.subjectId().equals(product.order().buildSessionId().value())
                && old.questionArtifactRef().equals(product.releaseSubject().reference()) && old.optionsSchemaId().equals(IncidentApplicationDecisionAuthority.OPTIONS_SCHEMA_ID)
                && old.minimumApprovers()==1 && old.requiredPermissions().equals(Set.of(IncidentApplicationDecisionAuthority.REQUIRED_PERMISSION))
                && old.policySnapshotRef().equals(product.workOrder().policy().reference()));
        require(lock(IncidentApplicationDecisionAuthority.subject(product,old.subjectVersion())).equals(product.releaseSubject()));
        requirePriorStages(product.order().tenantId(),product.order().buildSessionId(),IncidentApplicationIntent.Stage.RELEASE);
        require(stageIntent(product.order().tenantId(),product.order().buildSessionId(),IncidentApplicationIntent.Stage.RELEASE).isEmpty());
        require(product.activeOperationId().equals(completedStage(product,IncidentApplicationIntent.Stage.VERIFY).operationId()));
    }
    private Optional<IncidentApplicationReviewRenewal> rawRenewal(Connection c,TenantId tenant,BuildSessionId id) throws SQLException {
        try(var query=c.prepareStatement("SELECT * FROM factory_incident_review_renewal WHERE tenant_id=? AND build_session_id=?")) {
            query.setString(1,tenant.value()); query.setString(2,id.value());
            try(var rows=query.executeQuery()) {
                if(!rows.next()) return Optional.empty();
                var value=decode(rows.getString("renewal_json"),rows.getString("renewal_hash"),IncidentApplicationReviewRenewal.class);
                require(value.tenantId().equals(tenant) && value.buildSessionId().equals(id)
                        && value.previousDecisionPointId().value().equals(rows.getString("previous_decision_point_id"))
                        && value.decisionPointId().value().equals(rows.getString("decision_point_id")) && value.actionRunId().equals(rows.getString("action_run_id"))
                        && value.evidence().reference().value().equals(rows.getString("evidence_ref")) && value.evidence().contentHash().sha256().equals(rows.getString("evidence_hash")));
                return Optional.of(value);
            }
        }
    }
    private Optional<IncidentApplicationReviewRenewal> verifiedRenewal(Connection c,IncidentApplicationProduct product) throws SQLException {
        var found=rawRenewal(c,product.order().tenantId(),product.order().buildSessionId()); if(found.isEmpty()) return found;
        var value=found.orElseThrow(); var owner=runs.find(value.actionRunId()).orElseThrow(IncidentApplicationOrder::invalid);
        IncidentApplicationReviewRenewalAction.requireOwner(owner,product,value);
        require(owner.status()==ActionRunStatus.SUCCEEDED && IncidentApplicationReviewRenewalAction.result(value).equals(owner.result())
                && value.originalDeadlineAt().equals(product.order().deadlineAt()) && value.subject().equals(product.releaseSubject())
                && value.decisionPointId().equals(product.decisionPointId()) && product.version()>=value.reviewedProductVersion());
        var old=decisions.find(c,value.tenantId(),value.previousDecisionPointId(),false).orElseThrow(IncidentApplicationOrder::invalid);
        var point=decisions.find(c,value.tenantId(),value.decisionPointId(),false).orElseThrow(IncidentApplicationOrder::invalid);
        require(old.status()==DecisionPointStatus.OPEN && old.version()==0 && old.dueAt().equals(value.originalDeadlineAt())
                && old.subjectHash().equals(value.subject().hash()) && old.questionArtifactRef().equals(value.subject().reference())
                && old.subjectVersion()==value.inspectedVersion() && old.buildSessionId().equals(value.buildSessionId())
                && point.buildSessionId().equals(value.buildSessionId()) && point.subjectHash().equals(old.subjectHash())
                && point.subjectVersion()==old.subjectVersion() && point.type().equals(old.type()) && point.subjectType().equals(old.subjectType())
                && point.subjectId().equals(old.subjectId()) && point.questionArtifactRef().equals(old.questionArtifactRef())
                && point.optionsSchemaId().equals(old.optionsSchemaId()) && point.requiredPermissions().equals(old.requiredPermissions())
                && point.minimumApprovers()==old.minimumApprovers() && point.policySnapshotRef().equals(old.policySnapshotRef())
                && point.openedAt().equals(value.openedAt()) && point.dueAt().equals(value.deadlineAt()));
        artifact(c,value.tenantId(),lock(value.evidence()));
        require(lock(IncidentApplicationDecisionAuthority.subject(product,value.inspectedVersion())).equals(value.subject()));
        return found;
    }
    @Override public Optional<IncidentApplicationIntent> stageIntent(TenantId tenant,BuildSessionId session,IncidentApplicationIntent.Stage stage) {
        return connection(c->{ try(var query=c.prepareStatement("SELECT * FROM factory_incident_application_intent WHERE tenant_id=? AND build_session_id=? AND stage=? ORDER BY subject_version FETCH FIRST 2 ROWS ONLY")) {
            query.setString(1,tenant.value()); query.setString(2,session.value()); query.setString(3,stage.name());
            try(var rows=query.executeQuery()) { if(!rows.next()) return Optional.empty(); var value=operation(rows); require(!rows.next()); return Optional.of(value); }
        } });
    }
    @Override public void requirePriorStages(TenantId tenant,BuildSessionId session,IncidentApplicationIntent.Stage stage) {
        if(stage==IncidentApplicationIntent.Stage.BUILD) return;
        var product=find(tenant,session).orElseThrow(IncidentApplicationOrder::invalid);
        completedStage(product,IncidentApplicationIntent.Stage.BUILD);
        if(stage==IncidentApplicationIntent.Stage.RELEASE) completedStage(product,IncidentApplicationIntent.Stage.VERIFY);
    }
    private IncidentApplicationIntent completedStage(IncidentApplicationProduct product,IncidentApplicationIntent.Stage stage) {
        var operation=stageIntent(product.order().tenantId(),product.order().buildSessionId(),stage).orElseThrow(IncidentApplicationOrder::invalid);
        var owner=runs.find(operation.actionRunId()).orElseThrow(IncidentApplicationOrder::invalid);
        require(operation.status()==IncidentApplicationIntent.Status.COMPLETED && "INCIDENT_APPLICATION_OPERATION_COMPLETED".equals(operation.stableCode()));
        immutableOwner(owner,product,operation); require(owner.status()==ActionRunStatus.SUCCEEDED && IncidentApplicationRunner.exactResult(product,operation,owner));
        return operation;
    }
    @Override public List<IncidentApplicationProduct> active(int limit) {
        require(limit>0 && limit<=128);
        return connection(c->{ var result=new ArrayList<IncidentApplicationProduct>();
            try(var query=c.prepareStatement("SELECT * FROM factory_incident_application WHERE status NOT IN ('RELEASED','FAILED','MANUAL_REVIEW') ORDER BY updated_at,tenant_id,build_session_id FETCH FIRST ? ROWS ONLY")) {
                query.setInt(1,limit); try(var rows=query.executeQuery()) { while(rows.next()) result.add(product(rows)); }
            } return List.copyOf(result); });
    }
    @Override public List<IncidentApplicationIntent> pending(int limit) {
        require(limit>0 && limit<=128);
        return connection(c->{ var result=new ArrayList<IncidentApplicationIntent>();
            try(var query=c.prepareStatement("SELECT i.* FROM factory_incident_application_intent i WHERE i.status IN ('PENDING','RUNNING','EFFECT_COMMITTED') OR (i.status IN ('FAILED','MANUAL_REVIEW','CANCELLED') AND EXISTS (SELECT 1 FROM action_run a WHERE a.run_id=i.action_run_id AND a.status='WAITING_EXTERNAL')) ORDER BY i.created_at,i.operation_id FETCH FIRST ? ROWS ONLY")) {
                query.setInt(1,limit); try(var rows=query.executeQuery()) { while(rows.next()) result.add(operation(rows)); }
            } return List.copyOf(result); });
    }
    @Override public void requireCurrentComponent(IncidentApplicationOrder order, Instant now) {
        connection(c->{ currentComponent(c,order,now,false); return null; });
    }
    @Override public void requireReleaseApproval(TenantId tenant,BuildSessionId id) {
        connection(c->{ var value=required(c,tenant,id,false);
            require(value.status()==IncidentApplicationProduct.Status.REVIEW || value.status()==IncidentApplicationProduct.Status.RELEASING || value.status()==IncidentApplicationProduct.Status.RELEASED);
            var point=decisions.find(c,tenant,Objects.requireNonNull(value.decisionPointId()),false).orElseThrow(IncidentApplicationOrder::invalid);
            require(point.status()==DecisionPointStatus.APPROVED && point.version()==1 && point.type().equals(IncidentApplicationDecisionAuthority.DECISION_TYPE)
                    && point.subjectType().equals(IncidentApplicationDecisionAuthority.SUBJECT_TYPE) && point.subjectHash().equals(value.releaseSubject().hash())
                    && point.subjectId().equals(id.value()) && point.buildSessionId().equals(id) && point.requiredPermissions().equals(Set.of(IncidentApplicationDecisionAuthority.REQUIRED_PERMISSION))
                    && point.minimumApprovers()==1 && point.optionsSchemaId().equals(IncidentApplicationDecisionAuthority.OPTIONS_SCHEMA_ID)
                    && point.questionArtifactRef().equals(value.releaseSubject().reference()) && point.policySnapshotRef().equals(value.workOrder().policy().reference()));
            var decision=new JdbcDecisionRepository(source).find(tenant,point.terminalDecisionId().orElseThrow()).orElseThrow(IncidentApplicationOrder::invalid);
            IncidentApplicationReleaseDocuments.requireDecision(value,point,decision); return null;
        });
    }
    @Override public IncidentApplicationProduct accept(IncidentApplicationBuildWorkOrder workOrder, ActionRun owner, Instant observedAt) {
        return transaction(c->{ Instant now=now(); var order=workOrder.order();
            var existing=find(c,order.tenantId(),order.buildSessionId(),true);
            if(existing.isPresent()) { require(existing.orElseThrow().workOrder().equals(workOrder)); return existing.orElseThrow(); }
            require(sessions.find(c,order.tenantId(),order.buildSessionId(),true).isEmpty());
            currentComponent(c,order,now,true); full(order); live(order,now);
            var locked=lockAction(c,owner.runId()); require(locked.equals(owner)); requireIntakeOwner(owner,order);
            for(var lock:List.of(workOrder.requirements(),workOrder.blueprint(),workOrder.moduleCatalog(),workOrder.policy(),workOrder.workOrder())) artifact(c,order.tenantId(),lock);
            now=now(); live(order,now); currentComponent(c,order,now,false);
            var session=new BuildSession(order.buildSessionId(),order.tenantId(),order.projectId(),IncidentApplicationOrder.PRODUCT_LINE_ID,order.requestKey(),IncidentApplicationActions.OWNER,
                    BuildSessionStatus.RUNNING,BuildSessionPhase.BUILD,workOrder.requirements().reference(),workOrder.requirements().hash(),Optional.empty(),Optional.empty(),
                    Optional.of(workOrder.blueprint().reference()),Optional.empty(),Optional.empty(),Optional.empty(),0,0,now,order.deadlineAt(),Optional.empty(),Optional.empty(),Optional.empty(),0,now,now);
            sessions.create(c,session); var value=IncidentApplicationProduct.accepted(workOrder,now); insertProduct(c,value); return value;
        });
    }
    @Override public IncidentApplicationIntent prepare(TenantId tenant, BuildSessionId session, IncidentApplicationIntent.Stage stage,
            long expectedVersion, ActionRun owner, Instant observedAt) {
        return transaction(c->{ var value=required(c,tenant,session,true); var current=session(c,value,true); Instant now=now();
            require(value.version()==expectedVersion && !value.terminal()); live(c,value,current,now);
            if(stage!=IncidentApplicationIntent.Stage.RELEASE) live(value.order(),now);
            require(stage==IncidentApplicationIntent.Stage.BUILD && value.status()==IncidentApplicationProduct.Status.ACCEPTED
                    || stage==IncidentApplicationIntent.Stage.VERIFY && value.status()==IncidentApplicationProduct.Status.BUILT
                    || stage==IncidentApplicationIntent.Stage.RELEASE && value.status()==IncidentApplicationProduct.Status.REVIEW);
            if(stage==IncidentApplicationIntent.Stage.RELEASE) approved(c,value,true);
            requirePriorStages(tenant,session,stage);
            currentComponent(c,value.order(),now,true);
            var locked=lockAction(c,owner.runId()); require(locked.equals(owner)); requireStageOwner(owner,value,stage,expectedVersion);
            require(owner.status()==ActionRunStatus.RUNNING && owner.attemptToken()!=null && !owner.attemptToken().isBlank());
            String id="incident-operation-"+hash(owner.runId()+"\n"+owner.attemptToken());
            var operation=new IncidentApplicationIntent(id,tenant,session,stage,expectedVersion,owner.runId(),hash(owner.attemptToken()),
                    IncidentApplicationIntent.Status.PENDING,now,stage==IncidentApplicationIntent.Stage.RELEASE?executionDeadline(value):value.order().deadlineAt(),null,null,null,0);
            var status=switch(stage) { case BUILD -> IncidentApplicationProduct.Status.BUILDING; case VERIFY -> IncidentApplicationProduct.Status.VERIFYING; case RELEASE -> IncidentApplicationProduct.Status.RELEASING; };
            var updated=value.next(status,value.product(),value.verification(),value.decisionPointId(),value.releaseSubject(),null,null,id,
                    stage==IncidentApplicationIntent.Stage.RELEASE?owner.runId():null,null,now);
            casProduct(c,value,updated); insertIntent(c,operation);
            if(stage==IncidentApplicationIntent.Stage.RELEASE) project(c,current,BuildSessionStatus.RUNNING,BuildSessionPhase.PACKAGE_RELEASE,null,now);
            return operation;
        });
    }
    @Override public Optional<IncidentApplicationIntent> claim(String id, String claim, Instant observedAt) {
        return transaction(c->{ var observed=intent(c,id,false).orElseThrow(IncidentApplicationOrder::invalid);
            var value=required(c,observed.tenantId(),observed.buildSessionId(),true); var session=session(c,value,true); Instant now=now();
            var owner=lockAction(c,observed.actionRunId()); var current=intent(c,id,true).orElseThrow(IncidentApplicationOrder::invalid);
            if(current.status()==IncidentApplicationIntent.Status.EFFECT_COMMITTED) return Optional.of(current);
            if(current.status()!=IncidentApplicationIntent.Status.PENDING) return Optional.empty();
            require(value.activeOperationId().equals(id));
            try { immutableOwner(owner,value,current); }
            catch(RuntimeException invalid) { failLocked(c,value,session,current,"INCIDENT_APPLICATION_OWNER_UNCERTAIN",true,now); return Optional.empty(); }
            if(owner.status()==ActionRunStatus.CANCELLED && session.cancellationRequestedAt().isPresent()) {
                cancelLocked(c,value,current,now); return Optional.empty();
            }
            if(owner.status()==ActionRunStatus.RUNNING && now.isBefore(current.createdAt().plusSeconds(30))) return Optional.empty();
            if(owner.status()!=ActionRunStatus.WAITING_EXTERNAL || !Objects.equals(owner.externalOperationId(),id)) {
                failLocked(c,value,session,current,"INCIDENT_APPLICATION_OWNER_UNCERTAIN",true,now); return Optional.empty();
            }
            try { IncidentApplicationActions.requireDispatchedOwner(owner,value,current); }
            catch(RuntimeException invalid) { failLocked(c,value,session,current,"INCIDENT_APPLICATION_OWNER_UNCERTAIN",true,now); return Optional.empty(); }
            if(!now.isBefore(current.deadlineAt()) || session.cancellationRequestedAt().isPresent()) {
                failLocked(c,value,session,current,"INCIDENT_APPLICATION_DEADLINE_OR_CANCEL",false,now); return Optional.empty();
            }
            var claimed=current.change(IncidentApplicationIntent.Status.RUNNING,IncidentApplicationOrder.text(claim,128),now.plus(Duration.ofMinutes(5)),null);
            casIntent(c,current,claimed); return Optional.of(claimed);
        });
    }
    @Override public IncidentApplicationProduct commitBuild(IncidentApplicationIntent intent, IncidentApplicationPreparedProduct result, Instant observedAt) {
        return transaction(c->{ var locked=effect(c,intent,IncidentApplicationIntent.Stage.BUILD); var current=locked.product(); Instant now=now();
            for(var lock:List.of(result.candidate(),result.billOfMaterials(),result.bundle(),result.buildEvidence())) artifact(c,current.order().tenantId(),lock);
            now=commitTime(c,locked,intent);
            var updated=current.next(IncidentApplicationProduct.Status.BUILT,result,null,null,null,null,null,intent.operationId(),null,null,now);
            fenceAction(c,locked.action(),now); casProduct(c,current,updated); committed(c,intent); project(c,locked.session(),BuildSessionStatus.RUNNING,BuildSessionPhase.TEST,null,now); return updated;
        });
    }
    @Override public IncidentApplicationProduct commitVerification(IncidentApplicationIntent intent, IncidentApplicationWholeVerification result, Instant observedAt) {
        return transaction(c->{ var locked=effect(c,intent,IncidentApplicationIntent.Stage.VERIFY); var current=locked.product(); Instant now=now();
            artifact(c,current.order().tenantId(),result.report()); artifact(c,current.order().tenantId(),result.fixtureSuite());
            if(!result.passed()) {
                now=commitTime(c,locked,intent);
                var failed=current.next(IncidentApplicationProduct.Status.FAILED,current.product(),result,null,null,null,null,intent.operationId(),null,result.stableCode(),now);
                fenceAction(c,locked.action(),now); casProduct(c,current,failed); committed(c,intent); project(c,locked.session(),BuildSessionStatus.FAILED,BuildSessionPhase.TEST,result.stableCode(),now); return failed;
            }
            tool.validate(current.workOrder(),current.product(),result);
            now=commitTime(c,locked,intent);
            var staged=current.next(IncidentApplicationProduct.Status.VERIFYING,current.product(),result,null,null,null,null,intent.operationId(),null,null,now);
            Artifact subject=IncidentApplicationDecisionAuthority.subject(staged,current.version()+1);
            artifacts.store(c,subject); var pointId=new DecisionPointId("incident-review-"+hash(current.order().tenantId().value()+"\n"+subject.contentHash().sha256()));
            var point=new DecisionPoint(pointId,current.order().tenantId(),current.order().buildSessionId(),IncidentApplicationDecisionAuthority.DECISION_TYPE,DecisionPointStatus.OPEN,
                    IncidentApplicationDecisionAuthority.SUBJECT_TYPE,current.order().buildSessionId().value(),current.version()+1,subject.contentHash(),subject.reference(),
                    IncidentApplicationDecisionAuthority.OPTIONS_SCHEMA_ID,Set.of(IncidentApplicationDecisionAuthority.REQUIRED_PERMISSION),1,current.workOrder().policy().reference(),
                    now,current.order().deadlineAt(),Optional.empty(),Optional.empty(),0);
            decisions.create(c,point);
            var updated=current.next(IncidentApplicationProduct.Status.REVIEW,current.product(),result,pointId,lock(subject),null,null,intent.operationId(),null,null,now);
            fenceAction(c,locked.action(),now); casProduct(c,current,updated); committed(c,intent); project(c,locked.session(),BuildSessionStatus.WAITING_RELEASE_REVIEW,BuildSessionPhase.HUMAN_RELEASE_REVIEW,null,now); return updated;
        });
    }
    @Override public IncidentApplicationProduct commitRelease(IncidentApplicationIntent intent, Instant observedAt) {
        return transaction(c->{ var locked=effect(c,intent,IncidentApplicationIntent.Stage.RELEASE); var current=locked.product();
            var decision=approved(c,current,true); tool.validate(current.workOrder(),current.product(),current.verification());
            full(current.order()); Instant now=now(); live(c,current,locked.session(),now); require(now.isBefore(intent.leaseUntil()));
            var build=completedStage(current,IncidentApplicationIntent.Stage.BUILD);
            var verification=completedStage(current,IncidentApplicationIntent.Stage.VERIFY);
            var certificate=IncidentApplicationReleaseDocuments.certificate(current,intent,decision,build,verification,verifiedRenewal(c,current)); artifacts.store(c,certificate);
            var release=IncidentApplicationReleaseDocuments.release(current,intent,certificate); artifacts.store(c,release);
            now=commitTime(c,locked,intent);
            var updated=current.next(IncidentApplicationProduct.Status.RELEASED,current.product(),current.verification(),current.decisionPointId(),current.releaseSubject(),
                    lock(certificate),lock(release),intent.operationId(),intent.actionRunId(),"INCIDENT_APPLICATION_RELEASED",now);
            fenceAction(c,locked.action(),now); project(c,locked.session(),BuildSessionStatus.SUCCEEDED,BuildSessionPhase.COMPLETE,"INCIDENT_APPLICATION_RELEASED",now);
            casProduct(c,current,updated); committed(c,intent); return updated;
        });
    }
    @Override public void complete(IncidentApplicationIntent expected, ActionRun terminal, Instant observedAt) {
        transaction(c->{ var product=required(c,expected.tenantId(),expected.buildSessionId(),true); session(c,product,true);
            var action=lockAction(c,expected.actionRunId()); var current=intent(c,expected.operationId(),true).orElseThrow(IncidentApplicationOrder::invalid);
            require(current.equals(expected) && current.status()==IncidentApplicationIntent.Status.EFFECT_COMMITTED && action.equals(terminal));
            immutableOwner(action,product,current); require(IncidentApplicationRunner.exactResult(product,current,action));
            casIntent(c,current,current.change(IncidentApplicationIntent.Status.COMPLETED,current.claimToken(),current.leaseUntil(),"INCIDENT_APPLICATION_OPERATION_COMPLETED")); return null;
        });
    }
    @Override public void fail(IncidentApplicationIntent expected, String code, boolean uncertain, Instant observedAt) {
        transaction(c->{ var value=required(c,expected.tenantId(),expected.buildSessionId(),true); var session=session(c,value,true);
            lockAction(c,expected.actionRunId()); var current=intent(c,expected.operationId(),true).orElseThrow(IncidentApplicationOrder::invalid);
            if(!current.equals(expected) || current.terminal()) return null;
            if(current.status()==IncidentApplicationIntent.Status.EFFECT_COMMITTED) {
                require(uncertain);
                // Keep already committed terminal product/session truth immutable. The separate
                // intent quarantine makes release reads fail closed without pretending rollback.
                if(value.terminal()) {
                    casIntent(c,current,current.change(IncidentApplicationIntent.Status.MANUAL_REVIEW,current.claimToken(),current.leaseUntil(),code)); return null;
                }
            }
            failLocked(c,value,session,current,code,uncertain,now()); return null;
        });
    }
    @Override public void stop(TenantId tenant, BuildSessionId id, String code, Instant observedAt) {
        stop(tenant,id,-1,code,observedAt);
    }
    @Override public void stop(TenantId tenant, BuildSessionId id,long expectedVersion,String code,Instant observedAt) {
        transaction(c->{ var value=required(c,tenant,id,true); var session=session(c,value,true); if(value.terminal()) return null;
            if(expectedVersion>=0 && value.version()!=expectedVersion) return null;
            if("INCIDENT_APPLICATION_DEADLINE_EXCEEDED".equals(code) && now().isBefore(executionDeadline(value))) return null;
            if(value.activeOperationId()!=null) {
                var operation=intent(c,value.activeOperationId(),true).orElseThrow(IncidentApplicationOrder::invalid);
                if(!operation.terminal() && operation.status()!=IncidentApplicationIntent.Status.EFFECT_COMMITTED) {
                    failLocked(c,value,session,operation,code,operation.status()==IncidentApplicationIntent.Status.RUNNING,now()); return null;
                }
            }
            var updated=value.next(IncidentApplicationProduct.Status.FAILED,value.product(),value.verification(),value.decisionPointId(),value.releaseSubject(),null,null,
                    value.activeOperationId(),value.releaseActionRunId(),code,now()); casProduct(c,value,updated);
            project(c,session,BuildSessionStatus.FAILED,session.currentPhase(),code,now()); return null;
        });
    }
    @Override public void cancel(TenantId tenant, BuildSessionId id, Instant observedAt) {
        transaction(c->{ var value=required(c,tenant,id,true); var session=session(c,value,true);
            require(session.status()==BuildSessionStatus.CANCELLING && session.cancellationRequestedAt().isPresent());
            if(value.terminal()) return null;
            IncidentApplicationIntent intent=null;
            if(value.activeOperationId()!=null) {
                intent=intent(c,value.activeOperationId(),true).orElseThrow(IncidentApplicationOrder::invalid);
                require(intent.terminal());
            }
            cancelLocked(c,value,intent,now()); return null;
        });
    }
    private void cancelLocked(Connection c,IncidentApplicationProduct value,IncidentApplicationIntent intent,Instant now) throws SQLException {
        if(!value.terminal()) {
            var cancelled=value.next(IncidentApplicationProduct.Status.FAILED,value.product(),value.verification(),value.decisionPointId(),value.releaseSubject(),null,null,
                    value.activeOperationId(),value.releaseActionRunId(),"INCIDENT_APPLICATION_CANCELLED",now);
            casProduct(c,value,cancelled);
        }
        if(intent!=null && !intent.terminal()) casIntent(c,intent,intent.change(IncidentApplicationIntent.Status.CANCELLED,intent.claimToken(),intent.leaseUntil(),"INCIDENT_APPLICATION_CANCELLED"));
        // The generic cancellation coordinator alone confirms CANCELLING -> CANCELLED.
    }

    private Locked effect(Connection c, IncidentApplicationIntent expected, IncidentApplicationIntent.Stage stage) throws SQLException {
        var value=required(c,expected.tenantId(),expected.buildSessionId(),true); var session=session(c,value,true);
        if(stage==IncidentApplicationIntent.Stage.RELEASE) approved(c,value,true);
        currentComponent(c,value.order(),now(),true); full(value.order());
        var action=lockAction(c,expected.actionRunId()); var current=intent(c,expected.operationId(),true).orElseThrow(IncidentApplicationOrder::invalid);
        require(current.equals(expected) && current.stage()==stage && current.status()==IncidentApplicationIntent.Status.RUNNING
                && current.operationId().equals(value.activeOperationId()) && now().isBefore(current.leaseUntil()));
        immutableOwner(action,value,current); require(action.status()==ActionRunStatus.WAITING_EXTERNAL && current.operationId().equals(action.externalOperationId()));
        IncidentApplicationActions.requireDispatchedOwner(action,value,current);
        live(c,value,session,now()); if(stage!=IncidentApplicationIntent.Stage.RELEASE) live(value.order(),now());
        return new Locked(value,session,action);
    }
    private void committed(Connection c, IncidentApplicationIntent intent) throws SQLException {
        casIntent(c,intent,intent.change(IncidentApplicationIntent.Status.EFFECT_COMMITTED,intent.claimToken(),intent.leaseUntil(),"INCIDENT_APPLICATION_EFFECT_COMMITTED"));
    }
    private Instant commitTime(Connection c,Locked locked,IncidentApplicationIntent intent) throws SQLException {
        Instant now=now(); live(c,locked.product(),locked.session(),now); require(now.isBefore(intent.leaseUntil()) && now.isBefore(intent.deadlineAt()));
        if(intent.stage()!=IncidentApplicationIntent.Stage.RELEASE) live(locked.product().order(),now);
        if(intent.stage()==IncidentApplicationIntent.Stage.RELEASE) approved(c,locked.product(),false);
        currentComponent(c,locked.product().order(),now,false); return now;
    }
    private void failLocked(Connection c, IncidentApplicationProduct value, BuildSession session, IncidentApplicationIntent intent, String code, boolean uncertain, Instant now) throws SQLException {
        require(code.matches("[A-Z][A-Z0-9_]*")); if(value.terminal()) return;
        var updated=value.next(uncertain?IncidentApplicationProduct.Status.MANUAL_REVIEW:IncidentApplicationProduct.Status.FAILED,value.product(),value.verification(),value.decisionPointId(),
                value.releaseSubject(),null,null,value.activeOperationId(),value.releaseActionRunId(),code,now);
        casProduct(c,value,updated); casIntent(c,intent,intent.change(uncertain?IncidentApplicationIntent.Status.MANUAL_REVIEW:IncidentApplicationIntent.Status.FAILED,intent.claimToken(),intent.leaseUntil(),code));
        project(c,session,uncertain?BuildSessionStatus.MANUAL_REVIEW:BuildSessionStatus.FAILED,session.currentPhase(),code,now);
    }
    private DecisionPoint approved(Connection c, IncidentApplicationProduct value, boolean lock) throws SQLException {
        require(value.decisionPointId()!=null && value.releaseSubject()!=null && value.verification()!=null && value.verification().passed());
        if(lock) lockRow(c,"SELECT decision_point_id FROM factory_decision_point WHERE tenant_id=? AND decision_point_id=? FOR UPDATE",value.order().tenantId().value(),value.decisionPointId().value());
        var point=decisions.find(c,value.order().tenantId(),value.decisionPointId(),false).orElseThrow(IncidentApplicationOrder::invalid);
        require(point.type().equals(IncidentApplicationDecisionAuthority.DECISION_TYPE) && point.subjectType().equals(IncidentApplicationDecisionAuthority.SUBJECT_TYPE)
                && point.buildSessionId().equals(value.order().buildSessionId()) && point.subjectId().equals(value.order().buildSessionId().value())
                && point.subjectHash().equals(value.releaseSubject().hash()) && point.status()==DecisionPointStatus.APPROVED && point.version()==1
                && point.terminalDecisionId().isPresent() && point.decidedAt().isPresent() && point.decidedAt().orElseThrow().isBefore(point.dueAt())
                && point.policySnapshotRef().equals(value.workOrder().policy().reference()) && now().isBefore(point.dueAt())
                && point.minimumApprovers()==1 && point.requiredPermissions().equals(Set.of(IncidentApplicationDecisionAuthority.REQUIRED_PERMISSION))
                && point.optionsSchemaId().equals(IncidentApplicationDecisionAuthority.OPTIONS_SCHEMA_ID)
                && point.subjectVersion()==inspectedVersion(value,value.status()==IncidentApplicationProduct.Status.REVIEW?value.version():value.version()-1)
                && point.dueAt().equals(executionDeadline(value))
                && point.questionArtifactRef().equals(value.releaseSubject().reference()));
        var decision=new JdbcDecisionRepository(source).find(value.order().tenantId(),point.terminalDecisionId().orElseThrow()).orElseThrow(IncidentApplicationOrder::invalid);
        IncidentApplicationReleaseDocuments.requireDecision(value,point,decision);
        artifact(c,value.order().tenantId(),value.releaseSubject());
        require(IncidentApplicationDecisionAuthority.subject(value,point.subjectVersion()).contentHash().equals(value.releaseSubject().hash()));
        return point;
    }
    private void currentComponent(Connection c, IncidentApplicationOrder order, Instant now, boolean lock) throws SQLException {
        var certification=certifications.find(c,order.tenantId(),order.component().certificationId(),lock).orElseThrow(IncidentApplicationOrder::invalid);
        require(certification.status()==CertificationStatus.CERTIFIED && certification.revokedAt().isEmpty()
                && certification.certificationManifest().filter(order.component().certificationManifest()::equals).isPresent()
                && certification.certificationEvidence().filter(order.component().certificationEvidence()::equals).isPresent()
                && certification.inputLock().candidateHash().equals(order.component().candidateHash())
                && certification.inputLock().sourceManifest().equals(order.component().sourceManifest())
                && certification.inputLockArtifact().equals(order.component().inputLockManifest())
                && certification.expiresAt().map(now::isBefore).orElse(true) && !now.isBefore(certification.updatedAt()));
    }
    private void full(IncidentApplicationOrder order) { require(fullComponents.resolve(order.tenantId(),order.component()).reference().equals(order.component())); }
    private void artifact(Connection c, TenantId tenant, CertificationArtifactLock lock) throws SQLException {
        var item=artifacts.find(c,tenant,lock.reference()).orElseThrow(IncidentApplicationOrder::invalid);
        require(item.contentHash().equals(lock.hash()) && hash(item.content()).equals(lock.hash().sha256()));
    }
    private void live(IncidentApplicationOrder order, Instant now) { require(now.isBefore(order.deadlineAt())); }
    private void live(Connection c,IncidentApplicationProduct product,BuildSession session, Instant now) throws SQLException {
        Instant deadline=verifiedRenewal(c,product).map(IncidentApplicationReviewRenewal::deadlineAt).orElse(session.deadlineAt());
        require(!session.status().isTerminal() && session.cancellationRequestedAt().isEmpty() && !now.isBefore(session.updatedAt()) && now.isBefore(deadline));
    }
    private BuildSession session(Connection c,IncidentApplicationProduct value,boolean lock) throws SQLException {
        var session=sessions.find(c,value.order().tenantId(),value.order().buildSessionId(),lock).orElseThrow(IncidentApplicationOrder::invalid);
        require(session.productLineId().equals(IncidentApplicationOrder.PRODUCT_LINE_ID) && session.createdBy().equals(IncidentApplicationActions.OWNER)
                && session.projectId().equals(value.order().projectId()) && session.requestIdempotencyKey().equals(value.order().requestKey())
                && session.requirementsHash().equals(value.workOrder().requirements().hash()) && session.deadlineAt().equals(value.order().deadlineAt())); return session;
    }
    private void project(Connection c,BuildSession value,BuildSessionStatus status,BuildSessionPhase phase,String code,Instant now) throws SQLException {
        var next=new BuildSession(value.buildSessionId(),value.tenantId(),value.projectId(),value.productLineId(),value.requestIdempotencyKey(),value.createdBy(),status,phase,
                value.requirementsArtifactRef(),value.requirementsHash(),value.selectedManagerWorkerBinding(),value.selectedCodingWorkerBinding(),value.currentBlueprintRef(),
                value.currentCandidateId(),value.currentCandidateHash(),value.currentCertificationId(),value.repairRound(),value.maxRepairRounds(),value.startedAt(),value.deadlineAt(),
                value.cancellationRequestedAt(),Optional.ofNullable(code),code==null?Optional.empty():Optional.of("Incident application production: "+code),value.version()+1,value.createdAt(),now);
        require(sessions.compareAndSet(c,value,next));
    }
    private ActionRun lockAction(Connection c,String id) throws SQLException {
        lockRow(c,"SELECT run_id FROM action_run WHERE run_id=? FOR UPDATE",id);
        return runs.find(id).orElseThrow(IncidentApplicationOrder::invalid);
    }
    private void fenceAction(Connection c,ActionRun run,Instant now) throws SQLException {
        try(var update=c.prepareStatement("UPDATE action_run SET version=version+1,updated_at=? WHERE run_id=? AND version=? AND status='WAITING_EXTERNAL' AND attempt_token=?")) {
            // Action Runtime's JDBC schema stores epoch milliseconds, unlike Factory timestamp columns.
            update.setLong(1,now.toEpochMilli()); update.setString(2,run.runId()); update.setLong(3,run.version()); update.setString(4,run.attemptToken()); require(update.executeUpdate()==1);
        }
    }
    private static void requireIntakeOwner(ActionRun run,IncidentApplicationOrder order) {
        ownerScope(run,order,IncidentApplicationActions.INTAKE); require(run.status()==ActionRunStatus.RUNNING && run.duplicateKey().equals(order.requestKey()));
        require(Arrays.equals(canonical(run.input()),canonical(IncidentApplicationIntake.input(order))));
    }
    private static void requireStageOwner(ActionRun run,IncidentApplicationProduct product,IncidentApplicationIntent.Stage stage,long version) {
        ownerScope(run,product.order(),stage==IncidentApplicationIntent.Stage.RELEASE?IncidentApplicationActions.RELEASE:IncidentApplicationActions.STAGE);
        require(run.duplicateKey().equals(IncidentApplicationActions.stageKey(product,stage,version)));
        var input=new LinkedHashMap<>(IncidentApplicationActions.stageInput(product,stage)); input.put("expectedVersion",version);
        require(Arrays.equals(canonical(run.input()),canonical(input)));
    }
    public static void immutableOwner(ActionRun run,IncidentApplicationProduct product,IncidentApplicationIntent intent) {
        IncidentApplicationActions.requireStageOwner(run,product,intent);
    }
    private static void ownerScope(ActionRun run,IncidentApplicationOrder order,String action) {
        require(run.tenantId().equals(order.tenantId().value()) && run.actionId().equals(action) && run.userId().equals(IncidentApplicationActions.OWNER)
                && run.requesterId().equals(IncidentApplicationActions.OWNER) && run.requestChannel()==ActionRequestChannel.INTERNAL && run.proposerType()==ActionProposerType.SERVICE
                && run.traceId()!=null && !run.traceId().isBlank());
        var metadata=run.contextMetadata(); require(metadata.keySet().equals(Set.of("actor.permissions","resource.type","resource.id","resource.projectId"))
                && metadata.get("resource.type").equals(IncidentApplicationActions.RESOURCE) && metadata.get("resource.id").equals(order.buildSessionId().value())
                && metadata.get("resource.projectId").equals(order.projectId().value()) && metadata.get("actor.permissions") instanceof Collection<?>);
        var permissions=(Collection<?>)metadata.get("actor.permissions"); require(permissions.size()==1 && permissions.contains(action));
    }
    private Optional<IncidentApplicationProduct> find(Connection c,TenantId tenant,BuildSessionId session,boolean lock) throws SQLException {
        try(var query=c.prepareStatement("SELECT * FROM factory_incident_application WHERE tenant_id=? AND build_session_id=?"+(lock?" FOR UPDATE":""))) {
            query.setString(1,tenant.value()); query.setString(2,session.value()); try(var rows=query.executeQuery()) { return rows.next()?Optional.of(product(rows)):Optional.empty(); }
        }
    }
    private IncidentApplicationProduct required(Connection c,TenantId tenant,BuildSessionId session,boolean lock) throws SQLException { return find(c,tenant,session,lock).orElseThrow(IncidentApplicationOrder::invalid); }
    private Optional<IncidentApplicationIntent> intent(Connection c,String id,boolean lock) throws SQLException {
        try(var query=c.prepareStatement("SELECT * FROM factory_incident_application_intent WHERE operation_id=?"+(lock?" FOR UPDATE":""))) {
            query.setString(1,id); try(var rows=query.executeQuery()) { return rows.next()?Optional.of(operation(rows)):Optional.empty(); }
        }
    }
    private IncidentApplicationProduct product(ResultSet row) throws SQLException {
        var value=decode(row.getString("product_json"),row.getString("product_hash"),IncidentApplicationProduct.class);
        require(value.order().tenantId().value().equals(row.getString("tenant_id")) && value.order().buildSessionId().value().equals(row.getString("build_session_id"))
                && value.order().requestKey().equals(row.getString("request_key")) && value.status().name().equals(row.getString("status")) && value.version()==row.getLong("version")
                && IncidentApplicationOrder.PRODUCT_LINE_ID.value().equals(row.getString("product_line_id"))
                && value.order().component().certificationId().value().equals(row.getString("component_certification_id"))
                && value.order().component().candidateHash().sha256().equals(row.getString("component_candidate_hash"))
                && value.order().component().certificationManifest().reference().value().equals(row.getString("component_manifest_ref"))
                && value.order().component().certificationManifest().hash().sha256().equals(row.getString("component_manifest_hash"))
                && Objects.equals(value.productCertification()==null?null:value.productCertification().reference().value(),row.getString("certification_ref"))
                && Objects.equals(value.productCertification()==null?null:value.productCertification().hash().sha256(),row.getString("certification_hash"))
                && Objects.equals(value.releaseManifest()==null?null:value.releaseManifest().reference().value(),row.getString("release_ref"))
                && Objects.equals(value.releaseManifest()==null?null:value.releaseManifest().hash().sha256(),row.getString("release_hash"))
                && Objects.equals(value.releaseActionRunId(),row.getString("release_action_run_id")));
        return value;
    }
    private IncidentApplicationIntent operation(ResultSet row) throws SQLException {
        var value=decode(row.getString("intent_json"),row.getString("intent_hash"),IncidentApplicationIntent.class);
        require(value.operationId().equals(row.getString("operation_id")) && value.tenantId().value().equals(row.getString("tenant_id"))
                && value.buildSessionId().value().equals(row.getString("build_session_id")) && value.status().name().equals(row.getString("status"))
                && value.stage().name().equals(row.getString("stage")) && value.subjectVersion()==row.getLong("subject_version")
                && value.version()==row.getLong("version") && value.actionRunId().equals(row.getString("action_run_id")));
        return value;
    }
    private void insertProduct(Connection c,IncidentApplicationProduct value) throws SQLException {
        try(var update=c.prepareStatement("INSERT INTO factory_incident_application (tenant_id,build_session_id,product_line_id,request_key,component_certification_id,component_candidate_hash,component_manifest_ref,component_manifest_hash,status,product_json,product_hash,version,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            var order=value.order(); update.setString(1,order.tenantId().value()); update.setString(2,order.buildSessionId().value()); update.setString(3,IncidentApplicationOrder.PRODUCT_LINE_ID.value());
            update.setString(4,order.requestKey()); update.setString(5,order.component().certificationId().value()); update.setString(6,order.component().candidateHash().sha256());
            update.setString(7,order.component().certificationManifest().reference().value()); update.setString(8,order.component().certificationManifest().hash().sha256());
            update.setString(9,value.status().name()); String encoded=encode(value); update.setString(10,encoded); update.setString(11,hash(encoded)); update.setLong(12,value.version());
            JdbcPersistenceSupport.setInstant(update,13,value.updatedAt()); require(update.executeUpdate()==1);
        }
    }
    private void casProduct(Connection c,IncidentApplicationProduct expected,IncidentApplicationProduct value) throws SQLException {
        require(value.version()==expected.version()+1 && value.workOrder().equals(expected.workOrder()));
        try(var update=c.prepareStatement("UPDATE factory_incident_application SET status=?,product_json=?,product_hash=?,certification_ref=?,certification_hash=?,release_ref=?,release_hash=?,release_action_run_id=?,version=?,updated_at=? WHERE tenant_id=? AND build_session_id=? AND version=? AND product_hash=?")) {
            String encoded=encode(value); update.setString(1,value.status().name()); update.setString(2,encoded); update.setString(3,hash(encoded));
            update.setString(4,value.productCertification()==null?null:value.productCertification().reference().value()); update.setString(5,value.productCertification()==null?null:value.productCertification().hash().sha256());
            update.setString(6,value.releaseManifest()==null?null:value.releaseManifest().reference().value()); update.setString(7,value.releaseManifest()==null?null:value.releaseManifest().hash().sha256());
            update.setString(8,value.releaseActionRunId()); update.setLong(9,value.version()); JdbcPersistenceSupport.setInstant(update,10,value.updatedAt());
            update.setString(11,value.order().tenantId().value()); update.setString(12,value.order().buildSessionId().value()); update.setLong(13,expected.version()); update.setString(14,hash(encode(expected))); require(update.executeUpdate()==1);
        }
    }
    private void insertIntent(Connection c,IncidentApplicationIntent value) throws SQLException {
        try(var update=c.prepareStatement("INSERT INTO factory_incident_application_intent (operation_id,tenant_id,build_session_id,stage,subject_version,action_run_id,status,intent_json,intent_hash,version,created_at,deadline_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            update.setString(1,value.operationId()); update.setString(2,value.tenantId().value()); update.setString(3,value.buildSessionId().value()); update.setString(4,value.stage().name());
            update.setLong(5,value.subjectVersion()); update.setString(6,value.actionRunId()); update.setString(7,value.status().name()); String encoded=encode(value);
            update.setString(8,encoded); update.setString(9,hash(encoded)); update.setLong(10,value.version()); JdbcPersistenceSupport.setInstant(update,11,value.createdAt()); JdbcPersistenceSupport.setInstant(update,12,value.deadlineAt()); require(update.executeUpdate()==1);
        }
    }
    private void casIntent(Connection c,IncidentApplicationIntent expected,IncidentApplicationIntent value) throws SQLException {
        require(value.version()==expected.version()+1 && value.operationId().equals(expected.operationId()));
        try(var update=c.prepareStatement("UPDATE factory_incident_application_intent SET status=?,intent_json=?,intent_hash=?,version=? WHERE operation_id=? AND version=? AND intent_hash=?")) {
            String encoded=encode(value); update.setString(1,value.status().name()); update.setString(2,encoded); update.setString(3,hash(encoded)); update.setLong(4,value.version());
            update.setString(5,value.operationId()); update.setLong(6,expected.version()); update.setString(7,hash(encode(expected))); require(update.executeUpdate()==1);
        }
    }
    private String encode(Object value) { try { var encoded=json.writeValueAsString(value); require(encoded.getBytes(StandardCharsets.UTF_8).length<=256*1024); return encoded; } catch(java.io.IOException invalid) { throw IncidentApplicationOrder.invalid(); } }
    private <T>T decode(String encoded,String hash,Class<T> type) {
        require(encoded!=null && encoded.getBytes(StandardCharsets.UTF_8).length<=256*1024 && hash(encoded).equals(hash));
        try { var value=json.readValue(encoded,type); require(encode(value).equals(encoded)); return value; } catch(java.io.IOException invalid) { throw IncidentApplicationOrder.invalid(); }
    }
    private static void lockRow(Connection c,String sql,String... values) throws SQLException {
        try(var query=c.prepareStatement(sql)) { for(int i=0;i<values.length;i++) query.setString(i+1,values[i]); try(var rows=query.executeQuery()) { require(rows.next()); } }
    }
    private <T>T connection(SqlFunction<T> operation) {
        try(var c=source.getConnection()) { return operation.apply(c); } catch(SQLException invalid) { throw new IllegalStateException("INCIDENT_APPLICATION_PERSISTENCE_FAILED",invalid); }
    }
    private <T>T transaction(SqlFunction<T> operation) {
        return connection(c->{ boolean auto=c.getAutoCommit(); c.setAutoCommit(false);
            try { T value=operation.apply(c); c.commit(); return value; }
            catch(SQLException|RuntimeException failure) { c.rollback(); throw failure; }
            finally { c.setAutoCommit(auto); }
        });
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private record Locked(IncidentApplicationProduct product,BuildSession session,ActionRun action) { }
    @FunctionalInterface private interface SqlFunction<T> { T apply(Connection connection) throws SQLException; }
}
