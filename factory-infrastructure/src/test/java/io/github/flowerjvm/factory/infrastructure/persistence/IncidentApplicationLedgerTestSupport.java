package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.flowerjvm.factory.application.action.*;
import io.github.flowerjvm.factory.application.decision.*;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.audit.AuditSink;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.action.InMemoryActionRegistry;
import io.github.flowerjvm.flower.action.runtime.persistence.jdbc.*;
import io.github.flowerjvm.flower.action.runtime.audit.TraceSink;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/** Synthetic producer and tool fixtures; these tests prove JDBC/governance, not real product execution. */
final class IncidentApplicationLedgerTestSupport {
    static final Instant NOW=JdbcReferenceAssemblyIntakeTransactionTest.NOW;
    final DataSource dataSource;
    final JdbcReferenceAssemblyIntakeTransactionTest.Fixture producer;
    final MutableClock clock=new MutableClock(NOW);
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final JdbcArtifactStore artifacts;
    final JdbcBuildSessionRepository sessions;
    final JdbcDecisionPointRepository points;
    final JdbcDecisionRepository decisions;
    final JdbcRunStore runs;
    final IncidentApplicationProductionTool tool;
    final JdbcIncidentApplicationLedger ledger;
    final IncidentApplicationIntake intake;
    final IncidentApplicationActions controls;
    final DefaultActionRuntime runtime;
    final IncidentApplicationLauncher launcher;
    final IncidentApplicationRunner runner;
    final ActionBackedDecisionRecordLauncher decisionLauncher;
    final IncidentApplicationOrder order;
    final AtomicInteger builds=new AtomicInteger();
    final AtomicInteger verifies=new AtomicInteger();
    final java.util.concurrent.atomic.AtomicReference<Throwable> renewalFailure=new java.util.concurrent.atomic.AtomicReference<>();
    boolean verificationPass=true;

    IncidentApplicationLedgerTestSupport(DataSource source,String suffix) {
        dataSource=source; producer=new JdbcReferenceAssemblyIntakeTransactionTest.Fixture(source,suffix);
        artifacts=new JdbcArtifactStore(source,clock); sessions=new JdbcBuildSessionRepository(source);
        points=new JdbcDecisionPointRepository(source,mapper); decisions=new JdbcDecisionRepository(source);
        runs=new JdbcRunStore(source,mapper);
        order=new IncidentApplicationOrder(new BuildSessionId("incident-"+suffix),producer.tenant(),new ProjectId("incident-project"),
                "incident-request-"+suffix,IncidentApplicationOrder.Variant.BASIC,producer.component,NOW.plusSeconds(600));
        tool=new IncidentApplicationProductionTool() {
            public IncidentApplicationBuildWorkOrder plan(IncidentApplicationOrder selected) {
                var lock=document("fixture-work",Map.of("session",selected.buildSessionId().value(),"variant",selected.variant().name()));
                return new IncidentApplicationBuildWorkOrder(selected,lock,lock,lock,lock,lock,"synthetic-ledger-test-only");
            }
            public IncidentApplicationPreparedProduct produce(IncidentApplicationBuildWorkOrder work) {
                builds.incrementAndGet(); var lock=document("fixture-product",Map.of("workOrder",work.workOrder().hash().sha256()));
                return new IncidentApplicationPreparedProduct(lock,lock,lock,lock);
            }
            public IncidentApplicationWholeVerification verify(IncidentApplicationBuildWorkOrder work,IncidentApplicationPreparedProduct product) {
                verifies.incrementAndGet(); var lock=document("fixture-report",Map.of("passed",verificationPass));
                return new IncidentApplicationWholeVerification(verificationPass,verificationPass?"WHOLE_APPLICATION_PASSED":"WHOLE_APPLICATION_FAILED",lock,lock);
            }
            public void validate(IncidentApplicationBuildWorkOrder work,IncidentApplicationPreparedProduct product,IncidentApplicationWholeVerification result) {
                assertEquals(order,work.order()); assertTrue(result.passed());
                for(var lock:List.of(product.candidate(),product.billOfMaterials(),product.bundle(),product.buildEvidence(),result.report(),result.fixtureSuite()))
                    IncidentApplicationArtifacts.require(artifacts,order.tenantId(),lock);
            }
        };
        ledger=new JdbcIncidentApplicationLedger(source,mapper,runs,producer.gate(),tool,clock);
        intake=new IncidentApplicationIntake(producer.certification.certifications(),producer.gate(),tool,ledger,clock);
        controls=new IncidentApplicationActions(ledger,intake,runs,clock);
        var authority=new IncidentApplicationDecisionAuthority(ledger,clock);
        var admission=new DecisionRecordAdmission(sessions,points,decisions,List.of(authority),clock);
        var recording=new DecisionRecordingService(sessions,points,decisions,new JdbcDecisionRecordingTransaction(source,mapper),List.of(authority));
        var validators=new LinkedHashMap<String,io.github.flowerjvm.flower.action.runtime.validation.ActionInputValidator>();
        var policies=new LinkedHashMap<String,io.github.flowerjvm.flower.action.runtime.policy.PolicyGate>();
        var guards=new LinkedHashMap<String,io.github.flowerjvm.flower.action.runtime.guard.PreExecutionGuard>();
        var visibility=new LinkedHashMap<String,io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateVisibilityScopeResolver>();
        IncidentApplicationActions.IDS.forEach(id->{validators.put(id,controls);policies.put(id,controls);guards.put(id,controls);visibility.put(id,controls);});
        var renewalLedger=(IncidentApplicationLedger)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{IncidentApplicationLedger.class},(proxy,method,args)->{
            try { return method.invoke(ledger,args); }
            catch(java.lang.reflect.InvocationTargetException failure) {
                if(method.getName().equals("renewReview")) renewalFailure.set(failure.getCause());
                throw failure.getCause();
            }
        });
        var renewal=new IncidentApplicationReviewRenewalAction(renewalLedger,runs,clock);
        validators.put(IncidentApplicationReviewRenewalAction.ID,renewal); policies.put(IncidentApplicationReviewRenewalAction.ID,renewal);
        guards.put(IncidentApplicationReviewRenewalAction.ID,renewal); visibility.put(IncidentApplicationReviewRenewalAction.ID,renewal);
        validators.put(DecisionRecordAction.ACTION_ID,new DecisionRecordActionValidator());
        policies.put(DecisionRecordAction.ACTION_ID,new DecisionRecordPolicyGate(admission));
        guards.put(DecisionRecordAction.ACTION_ID,new DecisionRecordPreExecutionGuard(admission));
        visibility.put(DecisionRecordAction.ACTION_ID,new DecisionRecordVisibilityScopeResolver(admission));
        runtime=new DefaultActionRuntime(new InMemoryActionRegistry(List.of(controls.intakeExecutor(),controls.stageExecutor(),controls.releaseExecutor(),
                renewal.executor(),new DecisionRecordActionExecutor(recording,admission))),new FactoryActionInputValidatorRouter(validators),new FactoryActionPolicyGateRouter(policies),
                ApprovalGate.unsupported(),new JdbcDuplicateActionPolicy(source,mapper,new FactoryDuplicateVisibilityScopeRouter(visibility)),
                AuditSink.noop(),TraceSink.noop(),runs,new FactoryPreExecutionGuardRouter(guards));
        launcher=new IncidentApplicationLauncher(runtime); runner=new IncidentApplicationRunner(ledger,tool,producer.gate(),runs,runtime,clock);
        decisionLauncher=new ActionBackedDecisionRecordLauncher(runtime,clock);
    }

    CertificationArtifactLock document(String kind,Map<String,Object> values) {
        var artifact=IncidentApplicationArtifacts.document(order.tenantId(),kind,values); artifacts.store(artifact); return IncidentApplicationArtifacts.lock(artifact);
    }
    IncidentApplicationProduct product() { return ledger.find(order.tenantId(),order.buildSessionId()).orElseThrow(); }
    void accept() { assertEquals(ActionExecutionStatus.SUCCEEDED,launcher.submit(order.tenantId(),order.requestKey(),IncidentApplicationIntake.input(order)).status()); }
    void stage(IncidentApplicationIntent.Stage stage) { launcher.stage(product(),stage); runner.drain(8); }
    void inspect() { accept(); stage(IncidentApplicationIntent.Stage.BUILD); assertEquals(IncidentApplicationProduct.Status.BUILT,product().status());
        stage(IncidentApplicationIntent.Stage.VERIFY); assertEquals(IncidentApplicationProduct.Status.REVIEW,product().status()); }
    ActionExecutionResult approve(ContentHash subject) {
        var point=points.find(order.tenantId(),product().decisionPointId()).orElseThrow();
        var authority=new DecisionRecordAuthority(order.tenantId(),order.projectId(),"synthetic-human",
                Set.of(DecisionRecordAction.PERMISSION,IncidentApplicationDecisionAuthority.REQUIRED_PERMISSION),new ArtifactReference("fixture:operator-authority"));
        return decisionLauncher.record(authority,point.decisionPointId(),"fixture-decision-"+UUID.randomUUID(),
                new DecisionRecordInput(point.decisionPointId(),point.version(),subject,DecisionOutcome.APPROVE,Optional.of("Synthetic fixture approval, never real user evidence")));
    }
    int count(String table) throws Exception {
        if(!Set.of("factory_incident_application","factory_incident_application_intent").contains(table)) throw new IllegalArgumentException();
        try(var c=dataSource.getConnection();var s=c.createStatement();var rs=s.executeQuery("SELECT COUNT(*) FROM "+table)) { rs.next();return rs.getInt(1); }
    }
    static final class MutableClock extends Clock {
        Instant value; MutableClock(Instant value){this.value=value;}
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return value;}
    }
}
