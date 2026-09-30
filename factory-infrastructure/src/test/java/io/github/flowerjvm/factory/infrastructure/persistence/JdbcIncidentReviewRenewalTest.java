package io.github.flowerjvm.factory.infrastructure.persistence;

import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.application.decision.*;
import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.flower.action.runtime.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic products; real registered Actions, JDBC transactions and immutable approval evidence. */
class JdbcIncidentReviewRenewalTest {
    @Test void renewalPreservesExactInspectedBytesAndOriginalPointThenFreshDecisionReleases() { assertLifecycle(fixture("same_bytes")); }
    static void assertLifecycle(IncidentApplicationLedgerTestSupport f) {
        f.inspect(); var original=f.product(); var originalSession=f.sessions.find(f.order.tenantId(),f.order.buildSessionId()).orElseThrow();
        var originalPoint=f.points.find(f.order.tenantId(),original.decisionPointId()).orElseThrow();
        f.clock.value=f.order.deadlineAt();
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,f.approve(original.releaseSubject().hash()).status());
        var input=IncidentApplicationReviewRenewalAction.input(original,f.clock.instant().plusSeconds(3600));
        var result=renew(f,input); assertEquals(ActionExecutionStatus.SUCCEEDED,result.status(),result.toString());
        var renewed=f.product(); var proof=f.ledger.reviewRenewal(f.order.tenantId(),f.order.buildSessionId()).orElseThrow();
        assertEquals(5,renewed.version()); assertEquals(original.workOrder(),renewed.workOrder());
        assertEquals(original.product(),renewed.product()); assertEquals(original.verification(),renewed.verification());
        assertEquals(original.releaseSubject(),renewed.releaseSubject()); assertNotEquals(original.decisionPointId(),renewed.decisionPointId());
        assertEquals(originalPoint,f.points.find(f.order.tenantId(),original.decisionPointId()).orElseThrow());
        assertEquals(originalSession.deadlineAt(),f.sessions.find(f.order.tenantId(),f.order.buildSessionId()).orElseThrow().deadlineAt());
        assertEquals(4,proof.inspectedVersion()); assertEquals(5,proof.reviewedProductVersion());
        assertEquals(result,renew(f,input)); assertEquals(renewed,f.product());
        // The stale expiry observation made BEFORE renewal cannot fail the new review.
        f.ledger.stop(f.order.tenantId(),f.order.buildSessionId(),original.version(),"INCIDENT_APPLICATION_DEADLINE_EXCEEDED",f.clock.instant());
        assertEquals(renewed,f.product());
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,f.launcher.stage(renewed,IncidentApplicationIntent.Stage.RELEASE).status());
        assertEquals(ActionExecutionStatus.SUCCEEDED,f.approve(renewed.releaseSubject().hash()).status());
        f.stage(IncidentApplicationIntent.Stage.RELEASE); assertEquals(IncidentApplicationProduct.Status.RELEASED,f.product().status());
        assertEquals(7,f.product().version()); assertEquals(original.product().bundle(),f.product().product().bundle());
        assertEquals(1,f.builds.get()); assertEquals(1,f.verifies.get());
        var gate=new IncidentApplicationReleasedReadGate(f.ledger,f.sessions,f.points,f.decisions,f.artifacts,f.runs,f.producer.gate(),f.tool);
        assertEquals(f.product(),gate.resolve(f.order.tenantId(),f.order.buildSessionId()));
        assertEquals(proof,f.ledger.reviewRenewal(f.order.tenantId(),f.order.buildSessionId()).orElseThrow());
        assertEquals(result,renew(f,input));
    }
    @Test void liveReviewWrongSubjectUnboundedDeadlineAndSecondRenewalAreDenied() {
        var f=fixture("denials"); f.inspect(); var input=IncidentApplicationReviewRenewalAction.input(f.product(),f.order.deadlineAt().plusSeconds(3600));
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,renew(f,input).status());
        f.clock.value=f.order.deadlineAt(); var wrong=new HashMap<>(input); wrong.put("subjectHash","f".repeat(64));
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,renew(f,wrong).status());
        var far=new HashMap<>(input); far.put("deadlineAt",f.clock.instant().plusSeconds(172801).toString());
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,renew(f,far).status());
        assertEquals(ActionExecutionStatus.SUCCEEDED,renew(f,input).status());
        var different=new HashMap<>(input); different.put("deadlineAt",f.clock.instant().plusSeconds(7200).toString());
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,renew(f,different).status());
        assertEquals(5,f.product().version());
    }
    @Test void cancellationOrRevokedComponentCannotRenew() {
        var f=fixture("cancelled"); f.inspect(); var input=IncidentApplicationReviewRenewalAction.input(f.product(),f.order.deadlineAt().plusSeconds(3600));
        f.clock.value=f.order.deadlineAt(); var session=f.sessions.find(f.order.tenantId(),f.order.buildSessionId()).orElseThrow();
        assertTrue(f.sessions.compareAndSet(session,session.requestCancellation(f.clock.instant())));
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,renew(f,input).status()); assertEquals(4,f.product().version());
        var revoked=fixture("revoked"); revoked.inspect(); revoked.clock.value=revoked.order.deadlineAt();
        var cert=revoked.producer.certification.certifications().find(revoked.order.tenantId(),revoked.order.component().certificationId()).orElseThrow();
        assertTrue(revoked.producer.certification.certifications().compareAndSet(cert,cert.revoke("SYNTHETIC_REVOKED",revoked.clock.instant())));
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,renew(revoked,IncidentApplicationReviewRenewalAction.input(revoked.product(),revoked.clock.instant().plusSeconds(3600))).status());
    }
    @Test void newDeadlineEqualityPreventsApprovalAndFinalRelease() {
        var f=fixture("new_deadline"); f.inspect(); f.clock.value=f.order.deadlineAt();
        var due=f.clock.instant().plusSeconds(3600); assertEquals(ActionExecutionStatus.SUCCEEDED,renew(f,IncidentApplicationReviewRenewalAction.input(f.product(),due)).status());
        f.clock.value=due; assertNotEquals(ActionExecutionStatus.SUCCEEDED,f.approve(f.product().releaseSubject().hash()).status());
        f.ledger.stop(f.order.tenantId(),f.order.buildSessionId(),f.product().version(),"INCIDENT_APPLICATION_DEADLINE_EXCEEDED",f.clock.instant());
        assertEquals(IncidentApplicationProduct.Status.FAILED,f.product().status()); assertNull(f.product().releaseManifest());
    }
    @Test void releaseCommitRechecksTheRenewedDeadlineAfterApprovalAndDispatch() {
        var f=fixture("commit_deadline"); f.inspect(); f.clock.value=f.order.deadlineAt(); var due=f.clock.instant().plusSeconds(10);
        assertEquals(ActionExecutionStatus.SUCCEEDED,renew(f,IncidentApplicationReviewRenewalAction.input(f.product(),due)).status());
        assertEquals(ActionExecutionStatus.SUCCEEDED,f.approve(f.product().releaseSubject().hash()).status());
        assertEquals(ActionExecutionStatus.ACCEPTED,f.launcher.stage(f.product(),IncidentApplicationIntent.Stage.RELEASE).status());
        var intent=f.ledger.intent(f.product().activeOperationId()).orElseThrow(); var claim=f.ledger.claim(intent.operationId(),"synthetic-claim",f.clock.instant()).orElseThrow();
        f.clock.value=due; assertThrows(IllegalArgumentException.class,()->f.ledger.commitRelease(claim,f.clock.instant()));
        assertNull(f.product().releaseManifest());
    }
    @Test void concurrentRenewalsHaveOneImmutableWindowAndNoProductionRepeat() throws Exception { assertConcurrent(fixture("race")); }
    static void assertConcurrent(IncidentApplicationLedgerTestSupport f) throws Exception {
        f.inspect(); f.clock.value=f.order.deadlineAt(); var input=IncidentApplicationReviewRenewalAction.input(f.product(),f.clock.instant().plusSeconds(3600));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<ActionExecutionResult> task=()->{ assertTrue(start.await(5,TimeUnit.SECONDS)); return renew(f,input); };
            var first=pool.submit(task); var second=pool.submit(task); start.countDown();
            var a=first.get(30,TimeUnit.SECONDS); var b=second.get(30,TimeUnit.SECONDS);
            assertTrue(a.status()==ActionExecutionStatus.SUCCEEDED || b.status()==ActionExecutionStatus.SUCCEEDED);
        }
        assertEquals(5,f.product().version()); assertTrue(f.ledger.reviewRenewal(f.order.tenantId(),f.order.buildSessionId()).isPresent());
        assertEquals(ActionExecutionStatus.SUCCEEDED,renew(f,input).status()); assertEquals(1,f.builds.get()); assertEquals(1,f.verifies.get());
    }
    static ActionExecutionResult renew(IncidentApplicationLedgerTestSupport f,Map<String,Object> input) {
        String actor="synthetic-renewal-human";
        var proposal=ActionProposal.builder(IncidentApplicationReviewRenewalAction.ID).proposalId("fixture-renewal-"+UUID.randomUUID())
                .requestChannel(ActionRequestChannel.CLI).proposerType(ActionProposerType.USER).requesterId(actor)
                .idempotencyKey(IncidentApplicationReviewRenewalAction.key(f.order.tenantId(),input)).input(input).reason("Synthetic governed renewal test").build();
        var context=new ExecutionContext(f.order.tenantId().value(),actor,UUID.randomUUID().toString(),"fixture-renewal-trace",
                Map.of("actor.permissions",Set.of(IncidentApplicationReviewRenewalAction.ID),"actor.authoritySnapshotRef","fixture:renewal-authority",
                        "resource.type",IncidentApplicationActions.RESOURCE,"resource.id",input.get("buildSessionId"),"resource.projectId",input.get("projectId")));
        var result=f.runtime.handle(proposal,context);
        if(f.renewalFailure.get()!=null) throw new AssertionError("Synthetic renewal transaction failed",f.renewalFailure.get());
        return result;
    }
    static IncidentApplicationLedgerTestSupport fixture(String suffix) { return JdbcIncidentApplicationLedgerTest.fixture("renew_"+suffix); }
}
