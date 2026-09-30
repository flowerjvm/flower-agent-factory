package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.action.*;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.*;
import io.github.flowerjvm.flower.action.runtime.duplicate.*;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionGuard;
import io.github.flowerjvm.flower.action.runtime.run.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationReviewRenewalAction.*;

/** In-memory Action-pipeline fixtures only: no JDBC, native operator, real renewal or approval claims. */
public class IncidentApplicationReviewRenewalActionTest {
    static final String PRINCIPAL = "windows-sid:S-1-5-21-101";

    @Test void successfulPipelineRenewsOnlyOncePreservingTheOriginalSubjectAndSeparatingApproval() {
        var f = new Fixture(); var original = f.product;
        var first = f.call("first",PRINCIPAL,f.input); assertEquals(ActionExecutionStatus.SUCCEEDED,first.status());
        assertEquals(SUCCESS_CODE,first.code()); assertEquals(RetryDisposition.NEVER,first.retryDisposition());
        assertEquals(Set.of("decisionPointId","subjectHash"),first.output().keySet());
        assertEquals(original.releaseSubject(),f.product.releaseSubject()); assertSame(original.product(),f.product.product());
        assertSame(original.verification(),f.product.verification()); assertEquals(5,f.product.version());
        assertEquals(IncidentApplicationProduct.Status.REVIEW,f.product.status()); assertNull(f.product.productCertification());
        assertNull(f.product.releaseManifest()); assertNull(f.product.releaseActionRunId());
        assertEquals(first,f.call("duplicate",PRINCIPAL,f.input)); assertEquals(1,f.effects.get()); assertEquals(1,f.duplicates.accepted.get());
        assertEquals(ActionRunStatus.SUCCEEDED,f.runs.find("first").orElseThrow().status());
        requireOwner(f.runs.find("first").orElseThrow(),f.product,f.renewal);
    }

    @Test void deniedPrincipalCannotReadCachedResultAndPolicyRunsBeforeDuplicateLookup() {
        var f = new Fixture(); assertTrue(f.call("first",PRINCIPAL,f.input).terminalSuccess()); int reserves=f.duplicates.reservations.get();
        var result=f.call("denied",IncidentApplicationActions.OWNER,f.input);
        assertEquals(ActionExecutionStatus.DENIED,result.status()); assertTrue(result.output().isEmpty());
        assertEquals(reserves,f.duplicates.reservations.get()); assertEquals(1,f.effects.get());
    }

    @Test void otherwiseAuthorizedSecondResourceCannotReuseFirstResourcesRequestKeyOrOutput() {
        var f = new Fixture(); assertTrue(f.call("first",PRINCIPAL,f.input).terminalSuccess()); int reserves=f.duplicates.reservations.get();
        var other=new HashMap<>(f.input); other.put("buildSessionId","other-session"); other.put("projectId","other-project");
        var valid = f.proposal(PRINCIPAL,other,key(f.tenant,other)); var ctx=f.context("other-valid",PRINCIPAL,other);
        assertDoesNotThrow(()->authority(valid,ctx)); // B's own identity is valid; borrowing A's key is not.
        var aliased=f.proposal(PRINCIPAL,other,key(f.tenant,f.input));
        assertTrue(f.action.validate(aliased,definition(),ctx).valid());
        var denied=f.runtime.handle(aliased,ctx); assertEquals(ActionExecutionStatus.DENIED,denied.status());
        assertTrue(denied.output().isEmpty()); assertEquals(reserves,f.duplicates.reservations.get()); assertEquals(1,f.effects.get());
    }

    @Test void anotherAuthorizedPrincipalHasADifferentVisibilityScopeAndCannotReadPriorCachedResult() {
        var f = new Fixture(); assertTrue(f.call("first",PRINCIPAL,f.input).terminalSuccess());
        String other="windows-sid:S-1-5-21-202";
        assertNotEquals(f.action.resolve(f.proposal(PRINCIPAL,f.input,key(f.tenant,f.input)),f.context("a",PRINCIPAL,f.input)),
                f.action.resolve(f.proposal(other,f.input,key(f.tenant,f.input)),f.context("b",other,f.input)));
        var denied=f.call("other-human",other,f.input); assertEquals(ActionExecutionStatus.DENIED,denied.status());
        assertTrue(denied.output().isEmpty()); assertEquals(1,f.effects.get());
    }

    @Test void revokedCurrentAuthorityBlocksEvenCompletedDuplicateWithoutOutput() {
        var f = new Fixture(); assertTrue(f.call("first",PRINCIPAL,f.input).terminalSuccess()); f.current=false;
        int reserves=f.duplicates.reservations.get(); var denied=f.call("revoked",PRINCIPAL,f.input);
        assertEquals(ActionExecutionStatus.DENIED,denied.status()); assertTrue(denied.output().isEmpty());
        assertEquals(reserves,f.duplicates.reservations.get()); assertEquals(1,f.effects.get());
    }

    @Test void lastMomentGuardRechecksTheLedgerAndRejectsChangedAuthorityBeforeEffect() {
        var f = new Fixture(); var runtime=f.runtime((proposal,definition,context,policy)->{
            f.current=false; return f.action.check(proposal,definition,context,policy);
        });
        var result=runtime.handle(f.proposal(PRINCIPAL,f.input,key(f.tenant,f.input)),f.context("guard",PRINCIPAL,f.input));
        assertEquals(ActionExecutionStatus.DENIED,result.status()); assertTrue(result.output().isEmpty()); assertEquals(0,f.effects.get());
        assertEquals(1,f.admissionChecks.get()); assertEquals(1,f.executionChecks.get());
    }

    @Test void requestKeyBindsTenantAndEveryExactFieldWithStableIntegerSerialization() {
        var f = new Fixture(); String first=key(f.tenant,f.input);
        var integer=new HashMap<>(f.input); integer.put("expectedVersion",4); assertEquals(first,key(f.tenant,integer));
        var changes=Map.<String,Object>of("buildSessionId","other","projectId","other","previousDecisionPointId","other",
                "subjectHash","a".repeat(64),"deadlineAt",f.now.plusSeconds(600).toString());
        for(var entry:changes.entrySet()) {var changed=new HashMap<>(f.input); changed.put(entry.getKey(),entry.getValue()); assertNotEquals(first,key(f.tenant,changed));}
        assertNotEquals(first,key(new TenantId("other-tenant"),f.input));
        var v5=new HashMap<>(f.input); v5.put("expectedVersion",5); assertThrows(IllegalArgumentException.class,()->key(f.tenant,v5));
    }

    @Test void rejectsUnknownFieldsUnsafeTextWrongVersionAndNoncanonicalTimeWithoutDuplicateReservation() {
        var f = new Fixture(); var bad=new ArrayList<Map<String,Object>>();
        for(var entry:Map.<String,Object>of("buildSessionId","bad\nidentity","projectId"," ","expectedVersion",4.0,
                "subjectHash","A".repeat(64),"deadlineAt","2026-09-12T01:00:00.000Z").entrySet()) {
            var values=new HashMap<>(f.input); values.put(entry.getKey(),entry.getValue()); bad.add(values);
        }
        var extra=new HashMap<>(f.input); extra.put("approved",true); bad.add(extra);
        var precision=new HashMap<>(f.input); precision.put("deadlineAt","2026-09-12T01:00:00.000001Z"); bad.add(precision);
        for(var values:bad) {
            var proposal=f.proposal(PRINCIPAL,values,"invalid-key");
            var rejected=f.runtime.handle(proposal,f.context(UUID.randomUUID().toString(),PRINCIPAL,values));
            assertEquals(ActionExecutionStatus.VALIDATION_FAILED,rejected.status()); assertTrue(rejected.output().isEmpty());
        }
        assertEquals(0,f.duplicates.reservations.get()); assertEquals(0,f.effects.get());
    }

    @Test void exactAuthorityMetadataAndHumanChannelAreRequired() {
        var f=new Fixture(); var proposal=f.proposal(PRINCIPAL,f.input,key(f.tenant,f.input)); var context=f.context("metadata",PRINCIPAL,f.input);
        for(String missing:context.metadata().keySet()) {
            var metadata=new HashMap<>(context.metadata()); metadata.remove(missing);
            assertThrows(IllegalArgumentException.class,()->authority(proposal,new ExecutionContext(f.tenant.value(),PRINCIPAL,"run","trace",metadata)));
        }
        var extra=new HashMap<>(context.metadata()); extra.put("admin",true);
        assertThrows(IllegalArgumentException.class,()->authority(proposal,new ExecutionContext(f.tenant.value(),PRINCIPAL,"run","trace",extra)));
        var permissions=new HashMap<>(context.metadata()); permissions.put("actor.permissions",List.of(ID,ID));
        assertThrows(IllegalArgumentException.class,()->authority(proposal,new ExecutionContext(f.tenant.value(),PRINCIPAL,"run","trace",permissions)));
        assertThrows(IllegalArgumentException.class,()->authority(proposal.toBuilder().requestChannel(ActionRequestChannel.INTERNAL).build(),context));
        assertThrows(IllegalArgumentException.class,()->authority(proposal.toBuilder().requesterId("other").build(),context));
    }

    @Test void persistedOwnerAcceptsJdbcListPermissionsButRejectsModifiedBindingsAndSurvivesLaterProductVersion() {
        var f=new Fixture(); assertTrue(f.call("owner",PRINCIPAL,f.input).terminalSuccess()); var owner=f.runs.find("owner").orElseThrow();
        var listMetadata=new HashMap<>(owner.contextMetadata()); listMetadata.put("actor.permissions",List.of(ID));
        requireOwner(owner.toBuilder().contextMetadata(listMetadata).build(),f.product,f.renewal);
        for(var forged:List.of(owner.toBuilder().attemptToken("wrong").build(),owner.toBuilder().duplicateKey("wrong").build(),
                owner.toBuilder().tenantId("other").build(),owner.toBuilder().requesterId("other").build(),
                owner.toBuilder().dueAt(f.now.plusSeconds(1)).build()))
            assertThrows(IllegalArgumentException.class,()->requireOwner(forged,f.product,f.renewal));
        var advanced=new IncidentApplicationProduct(f.product.workOrder(),f.product.status(),f.product.product(),f.product.verification(),
                f.product.decisionPointId(),f.product.releaseSubject(),null,null,null,null,null,7,f.product.createdAt(),f.now.plusSeconds(1));
        requireOwner(owner,advanced,f.renewal); // immutable proof must not expire or depend on the current mutable version
    }

    @Test void executingOwnerAllowsSetContextAndJdbcListMetadataWithoutChangingIdentity() {
        var f=new Fixture(); var proposal=f.proposal(PRINCIPAL,f.input,key(f.tenant,f.input)); var context=f.context("direct",PRINCIPAL,f.input);
        var metadata=new HashMap<>(context.metadata()); metadata.put("actor.permissions",List.of(ID));
        f.runs.create(ActionRun.requested(proposal,context).toBuilder().status(ActionRunStatus.RUNNING).attemptToken("token").contextMetadata(metadata).build());
        var result=f.action.executor().execute(new ActionExecutionContext(context,proposal,definition(),f.input,"token"));
        assertTrue(result.terminalSuccess(),result.toString()); assertEquals(1,f.effects.get());
    }

    @Test void missingOrWrongRunningOwnerFailsClosedWithoutEffectOrAutomaticRetry() {
        var f=new Fixture(); var proposal=f.proposal(PRINCIPAL,f.input,key(f.tenant,f.input)); var context=f.context("missing",PRINCIPAL,f.input);
        var result=f.action.executor().execute(new ActionExecutionContext(context,proposal,definition(),f.input,"token"));
        assertEquals(RetryDisposition.MANUAL_REVIEW,result.retryDisposition()); assertTrue(result.output().isEmpty()); assertEquals(0,f.effects.get());
    }

    @Test void failureAfterCommitIsManualReviewAndDoesNotGrantSuccessOrRepeatTheEffect() {
        var f=new Fixture(); f.failAfterCommit=true;
        var first=f.call("uncertain",PRINCIPAL,f.input); assertEquals(RetryDisposition.MANUAL_REVIEW,first.retryDisposition());
        assertEquals(ActionExecutionStatus.FAILED,first.status()); assertTrue(first.output().isEmpty()); assertEquals(1,f.effects.get());
        assertEquals(first,f.call("retry",PRINCIPAL,f.input)); assertEquals(1,f.effects.get());
    }

    @Test void fullPipelineReserveVersusCompleteRaceHasOneAcceptOneEffectAndStableTerminalResult() throws Exception {
        var f=new Fixture(); var completing=new CountDownLatch(1); var finish=new CountDownLatch(1);
        f.duplicates.beforeComplete=()->{completing.countDown(); await(finish);};
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->f.call("first",PRINCIPAL,f.input)); assertTrue(completing.await(5,TimeUnit.SECONDS));
            var contender=workers.submit(()->f.call("contender",PRINCIPAL,f.input));
            var during=contender.get(5,TimeUnit.SECONDS); assertFalse(during.terminalSuccess()); assertTrue(during.output().isEmpty());
            finish.countDown(); var success=first.get(5,TimeUnit.SECONDS); assertTrue(success.terminalSuccess());
            assertEquals(1,f.duplicates.accepted.get()); assertEquals(1,f.effects.get());
            assertEquals(success,f.call("later",PRINCIPAL,f.input)); assertEquals(1,f.effects.get());
        } finally {finish.countDown();}
    }

    static void await(CountDownLatch latch) {try {assertTrue(latch.await(5,TimeUnit.SECONDS));} catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}}

    static final class Fixture {
        final IncidentApplicationFlowTest.Fixture base=new IncidentApplicationFlowTest.Fixture(IncidentApplicationFlowTest.Wait.REVIEW);
        final TenantId tenant=base.order.tenantId(); final Instant now=base.order.deadlineAt().plusSeconds(60);
        volatile IncidentApplicationProduct product=base.product; volatile IncidentApplicationReviewRenewal renewal;
        volatile boolean current=true,failAfterCommit;
        final AtomicInteger effects=new AtomicInteger(),admissionChecks=new AtomicInteger(),executionChecks=new AtomicInteger();
        final InMemoryRunStore runs=new InMemoryRunStore(); final IncidentApplicationLedger ledger;
        final IncidentApplicationReviewRenewalAction action; final CountingDuplicates duplicates; final DefaultActionRuntime runtime;
        final Map<String,Object> input=IncidentApplicationReviewRenewalAction.input(product,now.plusSeconds(3600));
        Fixture() {
            ledger=(IncidentApplicationLedger)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{IncidentApplicationLedger.class},(proxy,method,args)->{
                if(method.getName().equals("requireRenewal")) {
                    boolean executing=(Boolean)args[3]; (executing?executionChecks:admissionChecks).incrementAndGet();
                    if(!current || !tenant.equals(args[0]) || !base.order.buildSessionId().equals(args[1])
                            || !Arrays.equals(IncidentApplicationArtifacts.canonical(input),IncidentApplicationArtifacts.canonical(args[2]))
                            || executing && renewal!=null) throw IncidentApplicationOrder.invalid(); return null;
                }
                if(method.getName().equals("renewReview")) {
                    synchronized(this) {
                        if(!current || renewal!=null) throw IncidentApplicationOrder.invalid();
                        var owner=(ActionRun)args[3]; assertEquals(ActionRunStatus.RUNNING,owner.status());
                        renewal=new IncidentApplicationReviewRenewal(tenant,base.order.buildSessionId(),product.decisionPointId(),new DecisionPointId("renewed-point"),
                                product.releaseSubject(),4,5,base.order.deadlineAt(),now,Instant.parse((String)input.get("deadlineAt")),owner.runId(),
                                IncidentApplicationArtifacts.hash(owner.attemptToken()),key(tenant,input));
                        requireOwner(owner,product,renewal);
                        product=product.next(IncidentApplicationProduct.Status.REVIEW,product.product(),product.verification(),renewal.decisionPointId(),
                                product.releaseSubject(),null,null,null,null,null,now); effects.incrementAndGet();
                        if(failAfterCommit) throw new IllegalStateException("private-diagnostic-not-returned"); return renewal;
                    }
                }
                throw new AssertionError("Unexpected ledger call: "+method.getName());
            });
            action=new IncidentApplicationReviewRenewalAction(ledger,runs,Clock.fixed(now,ZoneOffset.UTC));
            duplicates=new CountingDuplicates(new InMemoryDuplicateActionPolicy(action)); runtime=runtime(action);
        }
        DefaultActionRuntime runtime(PreExecutionGuard guard) {return new DefaultActionRuntime(new InMemoryActionRegistry(List.of(action.executor())),action,action,
                ApprovalGate.unsupported(),duplicates,AuditSink.noop(),TraceSink.noop(),runs,guard);}
        ActionProposal proposal(String principal,Map<String,Object> input,String key) {return ActionProposal.builder(ID).requestChannel(ActionRequestChannel.CLI)
                .proposerType(ActionProposerType.USER).requesterId(principal).idempotencyKey(key).input(input).build();}
        ExecutionContext context(String run,String principal,Map<String,Object> input) {return new ExecutionContext(tenant.value(),principal,run,"renewal-trace",
                Map.of("actor.permissions",Set.of(ID),"actor.authoritySnapshotRef","local-incident-review-renewal-binding-sha256:"+"b".repeat(64),
                        "resource.type",RESOURCE,"resource.id",input.get("buildSessionId"),"resource.projectId",input.get("projectId")));}
        ActionExecutionResult call(String run,String principal,Map<String,Object> input) {return runtime.handle(proposal(principal,input,key(tenant,input)),context(run,principal,input));}
    }
    static final class CountingDuplicates implements DuplicateActionPolicy {
        final DuplicateActionPolicy delegate; final AtomicInteger reservations=new AtomicInteger(),accepted=new AtomicInteger(); volatile Runnable beforeComplete=()->{};
        CountingDuplicates(DuplicateActionPolicy delegate){this.delegate=delegate;}
        public DuplicateActionDecision reserve(ActionProposal proposal,ExecutionContext context){reservations.incrementAndGet();var decision=delegate.reserve(proposal,context);
            if(decision.type()==DuplicateActionDecisionType.ACCEPT) accepted.incrementAndGet();return decision;}
        public void complete(ActionProposal proposal,ExecutionContext context,ActionExecutionResult result){beforeComplete.run();delegate.complete(proposal,context,result);}
        public void release(ActionProposal proposal,ExecutionContext context,Throwable cause){delegate.release(proposal,context,cause);}
    }

    /** Standalone focused execution uses the same test methods when the parent owns the Maven lane. */
    public static void main(String[] args) throws Exception {
        int count=0; for(var method:IncidentApplicationReviewRenewalActionTest.class.getDeclaredMethods()) {
            if(method.isAnnotationPresent(Test.class)) {method.invoke(new IncidentApplicationReviewRenewalActionTest());count++;}
        }
        System.out.println("INCIDENT_APPLICATION_RENEWAL_ACTION_TESTS_PASSED="+count);
        for(var wait:List.of(IncidentApplicationFlowTest.Wait.REVIEW,IncidentApplicationFlowTest.Wait.RELEASE)) {
            var flow=new IncidentApplicationFlowTest();
            flow.renewedWindowRecoversEachWaitAfterOriginalDeadlineWithAllIdentitiesAndNoRedispatch(wait);
            flow.renewedWindowRecoversEachWaitThenExpiresExactlyAtPersistedNewDeadline(wait);
            flow.renewedWindowRecoversEachWaitCancellationWithAllIdentitiesWithoutExtendingAuthority(wait);
        }
        System.out.println("INCIDENT_APPLICATION_RENEWAL_FLOW_TESTS_PASSED=6");
    }
}
