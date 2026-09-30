package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.run.*;
import java.time.Clock;
import java.util.*;

/** Bounded host control lane; callers must not invoke drain from a Flower Worker tick. */
public final class IncidentApplicationRunner {
    private final IncidentApplicationLedger ledger;
    private final IncidentApplicationProductionTool tool;
    private final CertifiedAgentComponentReadGate components;
    private final RunStore runs;
    private final CompletableActionRuntime runtime;
    private final Clock clock;
    public IncidentApplicationRunner(IncidentApplicationLedger ledger, IncidentApplicationProductionTool tool,
            CertifiedAgentComponentReadGate components, RunStore runs, CompletableActionRuntime runtime, Clock clock) {
        this.ledger=Objects.requireNonNull(ledger); this.tool=Objects.requireNonNull(tool); this.components=Objects.requireNonNull(components);
        this.runs=Objects.requireNonNull(runs); this.runtime=Objects.requireNonNull(runtime); this.clock=Objects.requireNonNull(clock);
    }
    public int drain(int limit) {
        int observed=0;
        for(var intent:ledger.pending(limit)) {
            observed++;
            if(intent.terminal()) { finishFailure(intent); continue; }
            if(intent.status()==IncidentApplicationIntent.Status.RUNNING) {
                if(!clock.instant().isBefore(intent.leaseUntil()) || !clock.instant().isBefore(intent.deadlineAt())) {
                    ledger.fail(intent,"INCIDENT_APPLICATION_CLAIM_EXPIRED_UNCERTAIN",true,clock.instant()); finishFailure(intent);
                }
                continue;
            }
            if(intent.status()==IncidentApplicationIntent.Status.EFFECT_COMMITTED) { finishCommitted(intent); continue; }
            var claim=ledger.claim(intent.operationId(),"incident-claim-"+UUID.randomUUID(),clock.instant());
            if(claim.isEmpty()) { finishFailure(intent); continue; }
            var owned=claim.orElseThrow();
            if(owned.status()==IncidentApplicationIntent.Status.EFFECT_COMMITTED) { finishCommitted(owned); continue; }
            try {
                var product=ledger.find(owned.tenantId(),owned.buildSessionId()).orElseThrow(IncidentApplicationOrder::invalid);
                IncidentApplicationActions.require(components.resolve(owned.tenantId(),product.order().component()).reference().equals(product.order().component()));
                switch(owned.stage()) {
                    case BUILD -> ledger.commitBuild(owned,tool.produce(product.workOrder()),clock.instant());
                    case VERIFY -> ledger.commitVerification(owned,tool.verify(product.workOrder(),product.product()),clock.instant());
                    case RELEASE -> { tool.validate(product.workOrder(),product.product(),product.verification()); ledger.commitRelease(owned,clock.instant()); }
                }
                finishCommitted(ledger.intent(owned.operationId()).orElseThrow(IncidentApplicationOrder::invalid));
            } catch(RuntimeException uncertain) {
                // Unknown failures may follow artifact writes or a commit. Re-read durable truth
                // before marking uncertainty; never blindly invoke the production tool again.
                var after=ledger.intent(owned.operationId()).orElseThrow(IncidentApplicationOrder::invalid);
                if(after.status()==IncidentApplicationIntent.Status.EFFECT_COMMITTED) finishCommitted(after);
                else { ledger.fail(after,"INCIDENT_APPLICATION_STAGE_UNCERTAIN",true,clock.instant()); finishFailure(after); }
            }
        }
        return observed;
    }
    private void finishCommitted(IncidentApplicationIntent intent) {
        if(intent.status()!=IncidentApplicationIntent.Status.EFFECT_COMMITTED) return;
        var product=ledger.find(intent.tenantId(),intent.buildSessionId()).orElseThrow(IncidentApplicationOrder::invalid);
        var owner=runs.find(intent.actionRunId()).orElse(null);
        try { IncidentApplicationActions.requireDispatchedOwner(owner,product,intent); }
        catch(RuntimeException invalid) { quarantineCommitted(intent); return; }
        if(owner.status()==ActionRunStatus.WAITING_EXTERNAL) {
            runtime.complete(owner.runId(),owner.attemptToken(),result(product,intent));
            owner=runs.find(owner.runId()).orElse(null);
        }
        if(exactResult(product,intent,owner)) { ledger.complete(intent,owner,clock.instant()); return; }
        if(owner==null || owner.status().isTerminal() || !clock.instant().isBefore(intent.deadlineAt())
                || intent.leaseUntil()==null || !clock.instant().isBefore(intent.leaseUntil())) quarantineCommitted(intent);
    }
    private void quarantineCommitted(IncidentApplicationIntent intent) {
        ledger.fail(intent,"INCIDENT_APPLICATION_COMMITTED_OWNER_UNCERTAIN",true,clock.instant());
    }
    private void finishFailure(IncidentApplicationIntent original) {
        var intent=ledger.intent(original.operationId()).orElseThrow(IncidentApplicationOrder::invalid);
        if(!intent.terminal() || intent.status()==IncidentApplicationIntent.Status.COMPLETED) return;
        var owner=runs.find(intent.actionRunId()).orElse(null);
        if(owner!=null && owner.status()==ActionRunStatus.WAITING_EXTERNAL && intent.operationId().equals(owner.externalOperationId())) {
            var product=ledger.find(intent.tenantId(),intent.buildSessionId()).orElseThrow(IncidentApplicationOrder::invalid);
            try { IncidentApplicationActions.requireDispatchedOwner(owner,product,intent); } catch(RuntimeException invalid) { return; }
            runtime.complete(owner.runId(),owner.attemptToken(),ActionExecutionResult.manualReviewFailure(
                    intent.stableCode()==null?"INCIDENT_APPLICATION_STAGE_STOPPED":intent.stableCode(),"Production stage was stopped; no release authority was granted"));
        }
    }
    public static ActionExecutionResult result(IncidentApplicationProduct product,IncidentApplicationIntent intent) {
        var output=new LinkedHashMap<String,Object>(); output.put("buildSessionId",intent.buildSessionId().value()); output.put("operationId",intent.operationId());
        output.put("stage",intent.stage().name());
        switch(intent.stage()) {
            case BUILD -> { output.put("candidateHash",product.product().candidate().hash().sha256()); output.put("bundleHash",product.product().bundle().hash().sha256()); }
            case VERIFY -> { output.put("reportHash",product.verification().report().hash().sha256()); output.put("passed",product.verification().passed()); }
            case RELEASE -> { output.put("certificationHash",product.productCertification().hash().sha256()); output.put("releaseHash",product.releaseManifest().hash().sha256()); }
        }
        if(intent.stage()==IncidentApplicationIntent.Stage.VERIFY && !product.verification().passed())
            return new ActionExecutionResult(ActionExecutionStatus.FAILED,product.verification().stableCode(),"Whole application verification failed",output,RetryDisposition.AFTER_CORRECTION);
        return ActionExecutionResult.succeeded(output);
    }
    public static boolean exactResult(IncidentApplicationProduct product,IncidentApplicationIntent intent,ActionRun owner) {
        try { IncidentApplicationActions.requireDispatchedOwner(owner,product,intent); } catch(RuntimeException invalid) { return false; }
        if(owner==null || owner.result()==null || !owner.status().isTerminal() || !owner.runId().equals(intent.actionRunId())
                || !owner.tenantId().equals(intent.tenantId().value()) || !Objects.equals(owner.externalOperationId(),intent.operationId())
                || !IncidentApplicationArtifacts.hash(owner.attemptToken()).equals(intent.attemptTokenHash())) return false;
        var expected=result(product,intent);
        return (expected.status()==ActionExecutionStatus.SUCCEEDED?owner.status()==ActionRunStatus.SUCCEEDED:owner.status()==ActionRunStatus.FAILED)
                && expected.equals(owner.result());
    }
}
