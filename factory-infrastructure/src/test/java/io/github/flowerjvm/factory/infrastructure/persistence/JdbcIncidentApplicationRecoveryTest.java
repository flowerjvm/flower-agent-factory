package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.run.*;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Synthetic tools, real JDBC intent and registered Action owners; no real product certification. */
class JdbcIncidentApplicationRecoveryTest {
    @ParameterizedTest @EnumSource(IncidentApplicationIntent.Stage.class)
    void committedEffectCompletesItsCanonicalOwnerWithoutProducingAgain(IncidentApplicationIntent.Stage stage) {
        var f=fixture("complete_"+stage); var intent=committed(f,stage);
        int builds=f.builds.get(), verifies=f.verifies.get();
        f.runner.drain(8); f.runner.drain(8);
        assertEquals(IncidentApplicationIntent.Status.COMPLETED,f.ledger.intent(intent.operationId()).orElseThrow().status());
        assertEquals(builds,f.builds.get()); assertEquals(verifies,f.verifies.get());
    }
    @ParameterizedTest @EnumSource(IncidentApplicationIntent.Stage.class)
    void cancelledOwnerAfterEffectCommitIsQuarantinedWithoutPretendingRollback(IncidentApplicationIntent.Stage stage) {
        var f=fixture("cancelled_"+stage); var intent=committed(f,stage);
        f.runtime.cancel(intent.actionRunId(),"SYNTHETIC_CRASH_WINDOW_CANCELLATION");
        f.runner.drain(8); assertQuarantined(f,intent,stage);
    }
    @ParameterizedTest @EnumSource(IncidentApplicationIntent.Stage.class)
    void wrongTerminalResultAfterEffectCommitIsQuarantined(IncidentApplicationIntent.Stage stage) {
        var f=fixture("wrong_result_"+stage); var intent=committed(f,stage);
        var owner=f.runs.find(intent.actionRunId()).orElseThrow();
        f.runtime.complete(owner.runId(),owner.attemptToken(),ActionExecutionResult.manualReviewFailure("SYNTHETIC_OTHER_RESULT","Synthetic crash-window result"));
        f.runner.drain(8); assertQuarantined(f,intent,stage);
    }
    @ParameterizedTest @EnumSource(IncidentApplicationIntent.Stage.class)
    void missingOwnerObservationAfterEffectCommitIsQuarantined(IncidentApplicationIntent.Stage stage) {
        var f=fixture("missing_owner_"+stage); var intent=committed(f,stage);
        // Physical rows remain intact (their FK must not be bypassed). Simulate the read boundary
        // failing to resolve this owner; it must not become approval or redispatch authority.
        var invisible=(RunStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RunStore.class},(proxy,method,args)->{
            if(method.getName().equals("find") && intent.actionRunId().equals(args[0])) return Optional.empty();
            try { return method.invoke(f.runs,args); } catch(java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
        new IncidentApplicationRunner(f.ledger,f.tool,f.producer.gate(),invisible,f.runtime,f.clock).drain(8);
        assertQuarantined(f,intent,stage);
    }
    @Test void failurePublicationCrashGapIsReconciledWithoutClaimingAgain() {
        var f=fixture("failed_completion_gap"); f.accept(); f.launcher.stage(f.product(),IncidentApplicationIntent.Stage.BUILD);
        var pending=f.ledger.intent(f.product().activeOperationId()).orElseThrow();
        var claimed=f.ledger.claim(pending.operationId(),"synthetic-claim",f.clock.instant()).orElseThrow();
        f.ledger.fail(claimed,"SYNTHETIC_STAGE_FAILURE",true,f.clock.instant());
        assertEquals(ActionRunStatus.WAITING_EXTERNAL,f.runs.find(claimed.actionRunId()).orElseThrow().status());
        f.runner.drain(8);
        assertTrue(f.runs.find(claimed.actionRunId()).orElseThrow().status().isTerminal());
        assertTrue(f.ledger.pending(8).isEmpty()); assertEquals(0,f.builds.get());
    }
    @Test void revokedComponentDeniesEvenPreviouslySuccessfulReleaseDuplicate() {
        var f=fixture("revoked_duplicate"); f.inspect();
        assertEquals(ActionExecutionStatus.SUCCEEDED,f.approve(f.product().releaseSubject().hash()).status());
        var approved=f.product(); f.stage(IncidentApplicationIntent.Stage.RELEASE);
        assertEquals(IncidentApplicationProduct.Status.RELEASED,f.product().status());
        assertDoesNotThrow(()->f.ledger.requirePriorStages(f.order.tenantId(),f.order.buildSessionId(),IncidentApplicationIntent.Stage.RELEASE));
        assertDoesNotThrow(()->f.ledger.requireReleaseApproval(f.order.tenantId(),f.order.buildSessionId()));
        var replay=f.launcher.stage(approved,IncidentApplicationIntent.Stage.RELEASE);
        assertEquals(ActionExecutionStatus.SUCCEEDED,replay.status(),replay.toString());
        var old=f.producer.certification.certifications().find(f.order.tenantId(),f.order.component().certificationId()).orElseThrow();
        assertTrue(f.producer.certification.certifications().compareAndSet(old,old.revoke("SYNTHETIC_REVOKED",f.clock.instant())));
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,f.launcher.stage(approved,IncidentApplicationIntent.Stage.RELEASE).status());
    }
    @Test void deniedPrincipalAndPermissionCannotConsumeTheSuccessfulIntakeDuplicate() {
        var f=fixture("denied_cached_owner"); f.accept();
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,replay(f,"other-principal",Set.of(IncidentApplicationActions.INTAKE),f.order.buildSessionId().value()).status());
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,replay(f,IncidentApplicationActions.OWNER,Set.of(),f.order.buildSessionId().value()).status());
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,replay(f,IncidentApplicationActions.OWNER,Set.of("other.permission"),f.order.buildSessionId().value()).status());
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,replay(f,IncidentApplicationActions.OWNER,Set.of(IncidentApplicationActions.INTAKE,"other.permission"),f.order.buildSessionId().value()).status());
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,replay(f,IncidentApplicationActions.OWNER,List.of(IncidentApplicationActions.INTAKE,IncidentApplicationActions.INTAKE),f.order.buildSessionId().value()).status());
        assertNotEquals(ActionExecutionStatus.SUCCEEDED,replay(f,IncidentApplicationActions.OWNER,Set.of(IncidentApplicationActions.INTAKE),"another-resource").status());
        assertEquals(ActionExecutionStatus.SUCCEEDED,f.launcher.submit(f.order.tenantId(),f.order.requestKey(),IncidentApplicationIntake.input(f.order)).status());
    }
    static ActionExecutionResult replay(IncidentApplicationLedgerTestSupport f,String actor,Collection<String> permissions,String resource) {
        var proposal=ActionProposal.builder(IncidentApplicationActions.INTAKE).proposalId("synthetic-replay-"+UUID.randomUUID())
                .requestChannel(ActionRequestChannel.INTERNAL).proposerType(ActionProposerType.SERVICE).requesterId(actor)
                .idempotencyKey(f.order.requestKey()).input(IncidentApplicationIntake.input(f.order)).reason("Synthetic duplicate authorization test").build();
        var context=new ExecutionContext(f.order.tenantId().value(),actor,UUID.randomUUID().toString(),"synthetic-trace",
                Map.of("actor.permissions",permissions,"resource.type",IncidentApplicationActions.RESOURCE,"resource.id",resource,"resource.projectId",f.order.projectId().value()));
        return f.runtime.handle(proposal,context);
    }
    static IncidentApplicationLedgerTestSupport fixture(String suffix) { return JdbcIncidentApplicationLedgerTest.fixture("recovery_"+suffix); }
    static IncidentApplicationIntent committed(IncidentApplicationLedgerTestSupport f,IncidentApplicationIntent.Stage stage) {
        f.accept();
        if(stage!=IncidentApplicationIntent.Stage.BUILD) f.stage(IncidentApplicationIntent.Stage.BUILD);
        if(stage==IncidentApplicationIntent.Stage.RELEASE) {
            f.stage(IncidentApplicationIntent.Stage.VERIFY);
            assertEquals(ActionExecutionStatus.SUCCEEDED,f.approve(f.product().releaseSubject().hash()).status());
        }
        assertEquals(ActionExecutionStatus.ACCEPTED,f.launcher.stage(f.product(),stage).status());
        var pending=f.ledger.intent(f.product().activeOperationId()).orElseThrow();
        var claim=f.ledger.claim(pending.operationId(),"synthetic-effect-claim",f.clock.instant()).orElseThrow();
        switch(stage) {
            case BUILD -> f.ledger.commitBuild(claim,f.tool.produce(f.product().workOrder()),f.clock.instant());
            case VERIFY -> f.ledger.commitVerification(claim,f.tool.verify(f.product().workOrder(),f.product().product()),f.clock.instant());
            case RELEASE -> f.ledger.commitRelease(claim,f.clock.instant());
        }
        var effect=f.ledger.intent(pending.operationId()).orElseThrow();
        assertEquals(IncidentApplicationIntent.Status.EFFECT_COMMITTED,effect.status()); return effect;
    }
    static void assertQuarantined(IncidentApplicationLedgerTestSupport f,IncidentApplicationIntent before,IncidentApplicationIntent.Stage stage) {
        var intent=f.ledger.intent(before.operationId()).orElseThrow();
        assertEquals(IncidentApplicationIntent.Status.MANUAL_REVIEW,intent.status());
        assertEquals("INCIDENT_APPLICATION_COMMITTED_OWNER_UNCERTAIN",intent.stableCode());
        var product=f.product();
        if(stage==IncidentApplicationIntent.Stage.RELEASE) {
            assertEquals(IncidentApplicationProduct.Status.RELEASED,product.status());
            assertEquals(BuildSessionStatus.SUCCEEDED,f.sessions.find(f.order.tenantId(),f.order.buildSessionId()).orElseThrow().status());
            var gate=new IncidentApplicationReleasedReadGate(f.ledger,f.sessions,f.points,f.decisions,f.artifacts,f.runs,f.producer.gate(),f.tool);
            assertThrows(IllegalArgumentException.class,()->gate.resolve(f.order.tenantId(),f.order.buildSessionId()));
        } else assertEquals(IncidentApplicationProduct.Status.MANUAL_REVIEW,product.status());
        int builds=f.builds.get(),verifies=f.verifies.get(); f.runner.drain(8); f.runner.drain(8);
        assertEquals(builds,f.builds.get()); assertEquals(verifies,f.verifies.get()); assertEquals(product,f.product());
    }
}
