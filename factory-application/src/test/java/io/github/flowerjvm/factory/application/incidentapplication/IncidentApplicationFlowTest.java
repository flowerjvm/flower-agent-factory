package io.github.flowerjvm.factory.application.incidentapplication;

import static org.junit.jupiter.api.Assertions.*;
import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.decision.*;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineRegistry;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.*;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.core.flow.*;
import io.github.flowerjvm.flower.testkit.FlowTestHarness;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Synthetic orchestration tests. They do not produce an application, certify a fixture, or execute Docker. */
class IncidentApplicationFlowTest {
    static final Instant BASE=Instant.parse("2026-09-12T00:00:00Z");
    enum Wait { BUILD, VERIFY, REVIEW, RELEASE }
    @ParameterizedTest @EnumSource(Wait.class)
    void eachPersistedWaitRecoversAllSixIdentitiesWithoutRedispatchAndObservesSuccess(Wait wait) {
        var fixture=new Fixture(wait);
        try(var first=FlowTestHarness.builder().startMillis(BASE.toEpochMilli()).build()) {
            first.submit(fixture.factory().create(fixture.session,"actual-run-distinct","actual-trace-distinct")).ticks(4);
            assertIdentity(first,fixture);
            try(var recovered=first.restart()) {
                recovered.recoverActive(new FactoryProductLineRegistry(List.of(fixture.factory())).flowFactoryRegistry()).tick();
                assertIdentity(recovered,fixture); assertEquals(0,fixture.dispatches.get());
                recovered.ticks(3); assertEquals(0,fixture.dispatches.get());
                fixture.released(); recovered.ticks(4);
                assertEquals(FlowState.FINISHED,recovered.latestSnapshot(fixture.flowId()).orElseThrow().state());
                assertIdentity(recovered,fixture); assertEquals(0,fixture.dispatches.get());
            }
        }
    }
    @ParameterizedTest @EnumSource(Wait.class)
    void eachPersistedWaitRecoversThenExpiresAtTheStoredDeadlineWithoutRedispatch(Wait wait) {
        var fixture=new Fixture(wait);
        try(var first=FlowTestHarness.builder().startMillis(BASE.toEpochMilli()).build()) {
            first.submit(fixture.factory().create(fixture.session,"actual-run-distinct","actual-trace-distinct")).ticks(4);
            try(var recovered=first.restart()) {
                fixture.clock.at=fixture.order.deadlineAt().minusMillis(1);
                recovered.recoverActive(new FactoryProductLineRegistry(List.of(fixture.factory())).flowFactoryRegistry()).tick();
                assertIdentity(recovered,fixture); assertFalse(recovered.latestSnapshot(fixture.flowId()).orElseThrow().state().isTerminal());
                assertEquals(0,fixture.stops.get()); assertEquals(0,fixture.dispatches.get());
                fixture.clock.at=fixture.order.deadlineAt(); recovered.tick();
                assertEquals(FlowState.FAILED,recovered.latestSnapshot(fixture.flowId()).orElseThrow().state());
                assertIdentity(recovered,fixture); assertEquals(1,fixture.stops.get()); assertEquals(0,fixture.dispatches.get());
                assertEquals("INCIDENT_APPLICATION_DEADLINE_EXCEEDED",fixture.product.stableCode());
            }
        }
    }
    @ParameterizedTest @EnumSource(Wait.class)
    void eachPersistedWaitRecoversCancellationWithAllIdentitiesAndNoClaimOfExternalStop(Wait wait) {
        var fixture=new Fixture(wait);
        try(var first=FlowTestHarness.builder().startMillis(BASE.toEpochMilli()).build()) {
            first.submit(fixture.factory().create(fixture.session,"actual-run-distinct","actual-trace-distinct")).ticks(4);
            fixture.session=fixture.session.requestCancellation(BASE.plusSeconds(1));
            try(var recovered=first.restart()) {
                recovered.recoverActive(new FactoryProductLineRegistry(List.of(fixture.factory())).flowFactoryRegistry()).tick();
                assertIdentity(recovered,fixture);
                assertEquals(FlowState.FAILED,recovered.latestSnapshot(fixture.flowId()).orElseThrow().state());
                assertEquals(BuildSessionStatus.CANCELLING,fixture.session.status());
                assertEquals(0,fixture.dispatches.get()); assertEquals(0,fixture.stops.get());
                assertIdentity(recovered,fixture);
            }
        }
    }
    @Test void effectCommittedIsNotCompletedActionEvidenceAndDoesNotAdvanceNextStage() {
        var fixture=new Fixture(Wait.VERIFY);
        fixture.product=new IncidentApplicationProduct(fixture.workOrder,IncidentApplicationProduct.Status.BUILT,fixture.prepared,null,null,null,null,null,
                fixture.intent.operationId(),null,null,2,BASE,BASE);
        fixture.intent=new IncidentApplicationIntent(fixture.intent.operationId(),fixture.order.tenantId(),fixture.order.buildSessionId(),IncidentApplicationIntent.Stage.BUILD,0,
                fixture.intent.actionRunId(),fixture.intent.attemptTokenHash(),IncidentApplicationIntent.Status.EFFECT_COMMITTED,BASE,fixture.order.deadlineAt(),BASE.plusSeconds(300),"claim",null,2);
        try(var harness=FlowTestHarness.create()) {
            harness.submit(fixture.factory().create(fixture.session,"actual-run-distinct","actual-trace-distinct")).ticks(6);
            assertEquals("BUILD_APPLICATION",harness.latestSnapshot(fixture.flowId()).orElseThrow().currentStepId());
            assertEquals(0,fixture.dispatches.get());
        }
    }
    @ParameterizedTest @EnumSource(Wait.class)
    void committedEffectCheckpointStillHonorsDeadlineAndRetainsAllSixIdentities(Wait wait) {
        var fixture=new Fixture(wait);
        fixture.intent=new IncidentApplicationIntent(fixture.intent.operationId(),fixture.order.tenantId(),fixture.order.buildSessionId(),fixture.intent.stage(),0,
                fixture.intent.actionRunId(),fixture.intent.attemptTokenHash(),IncidentApplicationIntent.Status.EFFECT_COMMITTED,BASE,fixture.order.deadlineAt(),BASE.plusSeconds(300),"claim",null,2);
        try(var first=FlowTestHarness.builder().startMillis(BASE.toEpochMilli()).build()) {
            first.submit(fixture.factory().create(fixture.session,"actual-run-distinct","actual-trace-distinct")).tick();
            try(var recovered=first.restart()) {
                recovered.recoverActive(new FactoryProductLineRegistry(List.of(fixture.factory())).flowFactoryRegistry()).tick();
                assertIdentity(recovered,fixture); assertEquals(0,fixture.dispatches.get());
                fixture.clock.at=fixture.order.deadlineAt(); recovered.tick();
                assertEquals(FlowState.FAILED,recovered.latestSnapshot(fixture.flowId()).orElseThrow().state());
                assertIdentity(recovered,fixture); assertEquals(0,fixture.dispatches.get());
                assertEquals(IncidentApplicationIntent.Status.EFFECT_COMMITTED,fixture.intent.status()); // runner owns reconciliation
            }
        }
    }
    @Test void changingTenantCannotReadTheOtherOrdersProduct() {
        var fixture=new Fixture(Wait.BUILD);
        assertTrue(fixture.ledger.find(new TenantId("other-tenant"),fixture.order.buildSessionId()).isEmpty());
    }
    @Test void productCertificationCannotAppearOnAnUnreleasedSnapshot() {
        var fixture=new Fixture(Wait.VERIFY);
        assertThrows(IllegalArgumentException.class,()->new IncidentApplicationProduct(fixture.workOrder,IncidentApplicationProduct.Status.BUILT,
                fixture.prepared,null,null,null,lock("cert"),lock("release"),null,null,null,0,BASE,BASE));
    }
    @Test void orderRequiresMillisecondDeadlineAndSupportedComponentRole() {
        var fixture=new Fixture(Wait.BUILD);
        assertThrows(IllegalArgumentException.class,()->new IncidentApplicationOrder(fixture.order.buildSessionId(),fixture.order.tenantId(),fixture.order.projectId(),
                "key",IncidentApplicationOrder.Variant.BASIC,fixture.order.component(),BASE.plusNanos(1)));
    }
    @Test void changingVariantAndBomChangesCanonicalReviewSubject() {
        var basic=new Fixture(Wait.REVIEW);
        var before=IncidentApplicationDecisionAuthority.subject(basic.product,4);
        var changed=new IncidentApplicationPreparedProduct(basic.prepared.candidate(),lock("other-bom"),basic.prepared.bundle(),basic.prepared.buildEvidence());
        var product=new IncidentApplicationProduct(basic.workOrder,basic.product.status(),changed,basic.verification,basic.point.decisionPointId(),basic.product.releaseSubject(),
                null,null,basic.intent.operationId(),null,null,4,BASE,BASE);
        assertNotEquals(before.contentHash(),IncidentApplicationDecisionAuthority.subject(product,4).contentHash());
    }
    @ParameterizedTest @EnumSource(value=Wait.class,names={"REVIEW","RELEASE"})
    void renewedWindowRecoversEachWaitAfterOriginalDeadlineWithAllIdentitiesAndNoRedispatch(Wait wait) {
        var fixture=new Fixture(wait); fixture.renewedWindow(wait); var originalSubject=fixture.product.releaseSubject();
        try(var first=FlowTestHarness.builder().startMillis(fixture.clock.at.toEpochMilli()).build()) {
            first.submit(fixture.factory().create(fixture.session,"actual-run-distinct","actual-trace-distinct")).ticks(4);
            assertIdentity(first,fixture); assertEquals("REVIEW_AND_RELEASE_APPLICATION",first.latestSnapshot(fixture.flowId()).orElseThrow().currentStepId());
            try(var recovered=first.restart()) {
                recovered.recoverActive(new FactoryProductLineRegistry(List.of(fixture.factory())).flowFactoryRegistry()).tick();
                assertIdentity(recovered,fixture); assertFalse(recovered.latestSnapshot(fixture.flowId()).orElseThrow().state().isTerminal());
                assertEquals(fixture.order.deadlineAt(),fixture.session.deadlineAt());
                assertTrue(fixture.clock.at.isAfter(fixture.order.deadlineAt()));
                assertEquals(fixture.renewal.deadlineAt(),fixture.point.dueAt()); assertEquals(4,fixture.point.subjectVersion());
                assertEquals(wait==Wait.REVIEW?5:6,fixture.product.version()); assertEquals(originalSubject,fixture.product.releaseSubject());
                recovered.ticks(3); assertEquals(0,fixture.dispatches.get()); assertEquals(0,fixture.stops.get());
                fixture.released(); recovered.ticks(4);
                assertEquals(FlowState.FINISHED,recovered.latestSnapshot(fixture.flowId()).orElseThrow().state());
                assertIdentity(recovered,fixture); assertEquals(0,fixture.dispatches.get()); assertEquals(0,fixture.stops.get());
            }
        }
    }
    @ParameterizedTest @EnumSource(value=Wait.class,names={"REVIEW","RELEASE"})
    void renewedWindowRecoversEachWaitThenExpiresExactlyAtPersistedNewDeadline(Wait wait) {
        var fixture=new Fixture(wait); fixture.renewedWindow(wait);
        try(var first=FlowTestHarness.builder().startMillis(fixture.clock.at.toEpochMilli()).build()) {
            first.submit(fixture.factory().create(fixture.session,"actual-run-distinct","actual-trace-distinct")).ticks(4);
            try(var recovered=first.restart()) {
                fixture.clock.at=fixture.renewal.deadlineAt().minusMillis(1);
                recovered.recoverActive(new FactoryProductLineRegistry(List.of(fixture.factory())).flowFactoryRegistry()).tick();
                assertIdentity(recovered,fixture); assertFalse(recovered.latestSnapshot(fixture.flowId()).orElseThrow().state().isTerminal());
                assertEquals(0,fixture.dispatches.get()); assertEquals(0,fixture.stops.get());
                fixture.clock.at=fixture.renewal.deadlineAt(); recovered.tick();
                assertEquals(FlowState.FAILED,recovered.latestSnapshot(fixture.flowId()).orElseThrow().state());
                assertIdentity(recovered,fixture); assertEquals(0,fixture.dispatches.get()); assertEquals(1,fixture.stops.get());
                assertEquals("INCIDENT_APPLICATION_DEADLINE_EXCEEDED",fixture.product.stableCode());
                assertEquals(fixture.order.deadlineAt(),fixture.session.deadlineAt());
            }
        }
    }
    @ParameterizedTest @EnumSource(value=Wait.class,names={"REVIEW","RELEASE"})
    void renewedWindowRecoversEachWaitCancellationWithAllIdentitiesWithoutExtendingAuthority(Wait wait) {
        var fixture=new Fixture(wait); fixture.renewedWindow(wait);
        try(var first=FlowTestHarness.builder().startMillis(fixture.clock.at.toEpochMilli()).build()) {
            first.submit(fixture.factory().create(fixture.session,"actual-run-distinct","actual-trace-distinct")).ticks(4);
            fixture.session=fixture.session.requestCancellation(fixture.clock.at.plusMillis(1));
            try(var recovered=first.restart()) {
                recovered.recoverActive(new FactoryProductLineRegistry(List.of(fixture.factory())).flowFactoryRegistry()).tick();
                assertIdentity(recovered,fixture); assertEquals(FlowState.FAILED,recovered.latestSnapshot(fixture.flowId()).orElseThrow().state());
                assertEquals(BuildSessionStatus.CANCELLING,fixture.session.status()); assertEquals(0,fixture.dispatches.get()); assertEquals(0,fixture.stops.get());
                assertIdentity(recovered,fixture); assertNull(fixture.product.productCertification()); assertNull(fixture.product.releaseManifest());
            }
        }
    }
    private static void assertIdentity(FlowTestHarness harness,Fixture fixture) {
        var context=harness.latestSnapshot(fixture.flowId()).orElseThrow().executionContext();
        assertEquals(fixture.order.tenantId().value(),context.tenantIdOrNull()); assertEquals(IncidentApplicationActions.OWNER,context.userIdOrNull());
        assertEquals(fixture.order.buildSessionId().value(),context.sessionIdOrNull()); assertEquals("actual-run-distinct",context.runIdOrNull());
        assertEquals("actual-trace-distinct",context.traceIdOrNull()); assertEquals(fixture.order.projectId().value(),context.correlationIdOrNull());
    }
    static CertificationArtifactLock lock(String name) {
        return new CertificationArtifactLock(new ArtifactReference("artifact:"+name),new ContentHash(IncidentApplicationArtifacts.hash(name)));
    }
    static final class TestClock extends Clock {
        Instant at=BASE; @Override public ZoneId getZone(){return ZoneOffset.UTC;} @Override public Clock withZone(ZoneId zone){return this;} @Override public Instant instant(){return at;}
    }
    static final class Fixture {
        final TestClock clock=new TestClock(); final AtomicInteger dispatches=new AtomicInteger(); final AtomicInteger stops=new AtomicInteger();
        final IncidentApplicationOrder order; final IncidentApplicationBuildWorkOrder workOrder;
        final IncidentApplicationPreparedProduct prepared=new IncidentApplicationPreparedProduct(lock("candidate"),lock("bom"),lock("bundle"),lock("build-evidence"));
        final IncidentApplicationWholeVerification verification=new IncidentApplicationWholeVerification(true,"PASSED",lock("verify"),lock("suite"));
        IncidentApplicationProduct product; IncidentApplicationIntent intent; BuildSession session; DecisionPoint point;
        Instant effectiveDeadline; IncidentApplicationReviewRenewal renewal;
        final IncidentApplicationLedger ledger; final BuildSessionRepository sessions; final DecisionPointRepository points;
        Fixture(Wait wait) {
            var component=new CertifiedAgentComponentRef(CertifiedAgentComponentRef.SCHEMA_VERSION,"embedded-agent-pack",ProductLineId.AGENT_PACK,CertifiedArtifactType.AGENT_PACK,
                    new CertificationId("fixture-cert"),lock("component-cert"),new CandidateId("fixture-candidate"),lock("component-source").hash(),lock("source-manifest"),lock("input"),
                    new VerificationRunId("fixture-verification"),lock("component-verification"),lock("compatibility"),lock("component-evidence"),"local-demo");
            order=new IncidentApplicationOrder(new BuildSessionId("incident-order"),new TenantId("tenant-a"),new ProjectId("project-a"),"request-a",IncidentApplicationOrder.Variant.BASIC,component,BASE.plusSeconds(600));
            effectiveDeadline=order.deadlineAt();
            workOrder=new IncidentApplicationBuildWorkOrder(order,lock("requirements"),lock("blueprint"),lock("catalog"),lock("policy"),lock("work-order"),"deterministic-module-assembly");
            var pointId=new DecisionPointId("incident-point");
            point=new DecisionPoint(pointId,order.tenantId(),order.buildSessionId(),IncidentApplicationDecisionAuthority.DECISION_TYPE,DecisionPointStatus.OPEN,
                    IncidentApplicationDecisionAuthority.SUBJECT_TYPE,order.buildSessionId().value(),4,lock("subject").hash(),lock("subject").reference(),
                    IncidentApplicationDecisionAuthority.OPTIONS_SCHEMA_ID,Set.of(IncidentApplicationDecisionAuthority.REQUIRED_PERMISSION),1,workOrder.policy().reference(),BASE,order.deadlineAt(),Optional.empty(),Optional.empty(),0);
            var stage=wait==Wait.BUILD?IncidentApplicationIntent.Stage.BUILD:wait==Wait.RELEASE?IncidentApplicationIntent.Stage.RELEASE:IncidentApplicationIntent.Stage.VERIFY;
            intent=new IncidentApplicationIntent("operation",order.tenantId(),order.buildSessionId(),stage,0,"action",IncidentApplicationArtifacts.hash("token"),
                    wait==Wait.REVIEW?IncidentApplicationIntent.Status.COMPLETED:IncidentApplicationIntent.Status.RUNNING,BASE,order.deadlineAt(),BASE.plusSeconds(300),"claim","OPERATION",0);
            var status=switch(wait){case BUILD->IncidentApplicationProduct.Status.BUILDING;case VERIFY->IncidentApplicationProduct.Status.VERIFYING;
                case REVIEW->IncidentApplicationProduct.Status.REVIEW;case RELEASE->IncidentApplicationProduct.Status.RELEASING;};
            product=new IncidentApplicationProduct(workOrder,status,wait==Wait.BUILD?null:prepared,wait==Wait.REVIEW||wait==Wait.RELEASE?verification:null,
                    wait==Wait.REVIEW||wait==Wait.RELEASE?pointId:null,wait==Wait.REVIEW||wait==Wait.RELEASE?lock("subject"):null,null,null,intent.operationId(),null,null,4,BASE,BASE);
            session=new BuildSession(order.buildSessionId(),order.tenantId(),order.projectId(),IncidentApplicationOrder.PRODUCT_LINE_ID,order.requestKey(),IncidentApplicationActions.OWNER,
                    wait==Wait.REVIEW?BuildSessionStatus.WAITING_RELEASE_REVIEW:BuildSessionStatus.RUNNING,
                    wait==Wait.REVIEW?BuildSessionPhase.HUMAN_RELEASE_REVIEW:BuildSessionPhase.BUILD,workOrder.requirements().reference(),workOrder.requirements().hash(),
                    Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),0,0,BASE,order.deadlineAt(),Optional.empty(),Optional.empty(),Optional.empty(),0,BASE,BASE);
            ledger=(IncidentApplicationLedger)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{IncidentApplicationLedger.class},(proxy,method,args)->{
                if(method.getName().equals("find")) return order.tenantId().equals(args[0])&&order.buildSessionId().equals(args[1])?Optional.of(product):Optional.empty();
                if(method.getName().equals("intent")) return Optional.of(intent);
                if(method.getName().equals("executionDeadline")) return effectiveDeadline;
                if(method.getName().equals("stop")) {stops.incrementAndGet(); product=product.next(IncidentApplicationProduct.Status.FAILED,product.product(),product.verification(),product.decisionPointId(),
                        product.releaseSubject(),null,null,product.activeOperationId(),null,(String)args[args.length-2],clock.instant()); return null;}
                throw new AssertionError("Unexpected ledger call: "+method.getName());
            });
            sessions=(BuildSessionRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{BuildSessionRepository.class},(proxy,method,args)->{
                if(method.getName().equals("find")) return Optional.of(session); throw new AssertionError(method.getName());
            });
            points=(DecisionPointRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DecisionPointRepository.class},(proxy,method,args)->{
                if(method.getName().equals("find")) return Optional.of(point); throw new AssertionError(method.getName());
            });
        }
        IncidentApplicationFlowFactory factory() {
            return new IncidentApplicationFlowFactory(ledger,sessions,points,new IncidentApplicationLauncher((proposal,context)->{
                dispatches.incrementAndGet(); return new ActionExecutionResult(ActionExecutionStatus.ACCEPTED,"ACTION_DEFERRED","accepted",Map.of(),RetryDisposition.NEVER);
            }),clock);
        }
        FlowId flowId(){return FlowId.of(IncidentApplicationFlowFactory.FLOW_TYPE,order.buildSessionId().value());}
        /** Models already-persisted governed evidence; Action/SQL owner validation is tested separately. */
        void renewedWindow(Wait wait) {
            clock.at=order.deadlineAt().plusSeconds(60); effectiveDeadline=clock.at.plusSeconds(600);
            var oldPoint=point; var newPointId=new DecisionPointId("renewed-incident-point");
            renewal=new IncidentApplicationReviewRenewal(order.tenantId(),order.buildSessionId(),oldPoint.decisionPointId(),newPointId,
                    product.releaseSubject(),4,5,order.deadlineAt(),clock.at,effectiveDeadline,"renewal-action",IncidentApplicationArtifacts.hash("renewal-token"),"renewal-request-key");
            point=new DecisionPoint(newPointId,order.tenantId(),order.buildSessionId(),oldPoint.type(),wait==Wait.REVIEW?DecisionPointStatus.OPEN:DecisionPointStatus.APPROVED,
                    oldPoint.subjectType(),oldPoint.subjectId(),4,oldPoint.subjectHash(),oldPoint.questionArtifactRef(),oldPoint.optionsSchemaId(),oldPoint.requiredPermissions(),
                    oldPoint.minimumApprovers(),oldPoint.policySnapshotRef(),clock.at,effectiveDeadline,
                    wait==Wait.REVIEW?Optional.empty():Optional.of(clock.at),wait==Wait.REVIEW?Optional.empty():Optional.of(new DecisionId("new-point-decision")),wait==Wait.REVIEW?0:1);
            product=new IncidentApplicationProduct(workOrder,wait==Wait.REVIEW?IncidentApplicationProduct.Status.REVIEW:IncidentApplicationProduct.Status.RELEASING,
                    prepared,verification,newPointId,product.releaseSubject(),null,null,intent.operationId(),null,null,wait==Wait.REVIEW?5:6,BASE,clock.at);
            if(wait==Wait.RELEASE) intent=new IncidentApplicationIntent(intent.operationId(),order.tenantId(),order.buildSessionId(),IncidentApplicationIntent.Stage.RELEASE,5,
                    intent.actionRunId(),intent.attemptTokenHash(),IncidentApplicationIntent.Status.RUNNING,clock.at,effectiveDeadline,clock.at.plusSeconds(300),"claim",null,0);
        }
        void released() {
            product=new IncidentApplicationProduct(workOrder,IncidentApplicationProduct.Status.RELEASED,prepared,verification,point.decisionPointId(),lock("subject"),lock("cert"),lock("release"),
                    intent.operationId(),"release-action",null,renewal==null?6:7,BASE,clock.instant());
            intent=new IncidentApplicationIntent(intent.operationId(),order.tenantId(),order.buildSessionId(),IncidentApplicationIntent.Stage.RELEASE,renewal==null?4:5,"release-action",intent.attemptTokenHash(),
                    IncidentApplicationIntent.Status.COMPLETED,clock.instant(),effectiveDeadline,clock.instant().plusSeconds(300),"claim","INCIDENT_APPLICATION_OPERATION_COMPLETED",4);
        }
    }
}
