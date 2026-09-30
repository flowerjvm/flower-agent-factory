package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.decision.*;
import io.github.flowerjvm.factory.application.flow.*;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.core.context.ExecutionContext;
import io.github.flowerjvm.flower.core.flow.*;
import io.github.flowerjvm.flower.core.step.*;
import java.time.Clock;
import java.util.*;

/** Non-blocking primary line; persisted product/Action/intent facts, not Futures, own progress. */
public final class IncidentApplicationFlowFactory implements FactoryProductLine {
    public static final String FLOW_TYPE = "build-incident-application";
    public static final String DEFINITION_VERSION = FLOW_TYPE + ".v1";
    private final IncidentApplicationLedger ledger;
    private final BuildSessionRepository sessions;
    private final DecisionPointRepository decisions;
    private final IncidentApplicationLauncher launcher;
    private final Clock clock;
    public IncidentApplicationFlowFactory(IncidentApplicationLedger ledger, BuildSessionRepository sessions,
            DecisionPointRepository decisions, IncidentApplicationLauncher launcher, Clock clock) {
        this.ledger=Objects.requireNonNull(ledger); this.sessions=Objects.requireNonNull(sessions); this.decisions=Objects.requireNonNull(decisions);
        this.launcher=Objects.requireNonNull(launcher); this.clock=Objects.requireNonNull(clock);
    }
    @Override public ProductLineId productLineId() { return IncidentApplicationOrder.PRODUCT_LINE_ID; }
    @Override public String flowType() { return FLOW_TYPE; }
    @Override public boolean requiresProductLineCancellationRequester() { return true; }
    @Override public Flow create(BuildSession session, String runId, String traceId) {
        IncidentApplicationActions.require(session.productLineId().equals(productLineId()) && !session.status().isTerminal());
        return build(session.buildSessionId(),FactoryExecutionContexts.create(session.buildSessionId(),session.tenantId().value(),session.createdBy(),runId,traceId,session.projectId().value()));
    }
    @Override public Flow create(FlowId id) {
        IncidentApplicationActions.require(FLOW_TYPE.equals(id.flowType())); return build(new BuildSessionId(id.flowKey()),ExecutionContext.empty());
    }
    private Flow build(BuildSessionId session, ExecutionContext identity) {
        return Flow.builder(FLOW_TYPE,session.value()).definitionVersion(DEFINITION_VERSION).executionContext(identity).durable()
                .durableStep("BUILD_APPLICATION", step(session,IncidentApplicationIntent.Stage.BUILD),RecoveryPolicy.REENTER_IDEMPOTENT)
                .durableStep("VERIFY_WHOLE_APPLICATION",step(session,IncidentApplicationIntent.Stage.VERIFY),RecoveryPolicy.REENTER_IDEMPOTENT)
                .durableStep("REVIEW_AND_RELEASE_APPLICATION",step(session,IncidentApplicationIntent.Stage.RELEASE),RecoveryPolicy.REENTER_IDEMPOTENT).build();
    }
    private Step step(BuildSessionId session,IncidentApplicationIntent.Stage stage) {
        return new Step() {
            @Override protected StepResult onTick(StepContext context) {
                var identity=context.executionContext(); var tenant=new TenantId(identity.tenantIdOrNull());
                var current=sessions.find(tenant,session).orElseThrow(IncidentApplicationOrder::invalid);
                var product=ledger.find(tenant,session).orElseThrow(IncidentApplicationOrder::invalid);
                IncidentApplicationActions.require(current.productLineId().equals(productLineId())
                        && current.createdBy().equals(identity.userIdOrNull()) && session.value().equals(identity.sessionIdOrNull())
                        && current.projectId().value().equals(identity.correlationIdOrNull())
                        && identity.runIdOrNull()!=null && identity.traceIdOrNull()!=null);
                if (product.activeOperationId()!=null) {
                    var operation=ledger.intent(product.activeOperationId()).orElseThrow(IncidentApplicationOrder::invalid);
                    if(operation.status()==IncidentApplicationIntent.Status.EFFECT_COMMITTED) {
                        // Completion reconciliation belongs to the off-tick runner. A checkpoint
                        // may wait for it, but may not suppress a persisted deadline or cancellation.
                        if(current.cancellationRequestedAt().isPresent() || !clock.instant().isBefore(ledger.executionDeadline(product)))
                            return StepResult.fail(new IllegalStateException("INCIDENT_APPLICATION_COMMITTED_OWNER_UNCERTAIN"));
                        return StepResult.stay();
                    }
                    if(product.status()==IncidentApplicationProduct.Status.RELEASED) {
                        if(operation.status()==IncidentApplicationIntent.Status.COMPLETED) return StepResult.done();
                        return StepResult.fail(new IllegalStateException("INCIDENT_APPLICATION_RELEASE_OWNER_UNCERTAIN"));
                    }
                }
                if (product.terminal() || current.cancellationRequestedAt().isPresent()) return StepResult.fail(new IllegalStateException("INCIDENT_APPLICATION_STOPPED"));
                if (!clock.instant().isBefore(ledger.executionDeadline(product))) {
                    ledger.stop(tenant,session,product.version(),"INCIDENT_APPLICATION_DEADLINE_EXCEEDED",clock.instant());
                    if(!ledger.find(tenant,session).orElseThrow(IncidentApplicationOrder::invalid).terminal()) return StepResult.stay();
                    return StepResult.fail(new IllegalStateException("INCIDENT_APPLICATION_DEADLINE_EXCEEDED"));
                }
                boolean built=product.product()!=null;
                if (stage==IncidentApplicationIntent.Stage.BUILD && built) return StepResult.done();
                if (stage==IncidentApplicationIntent.Stage.VERIFY && product.verification()!=null) return StepResult.done();
                if (stage==IncidentApplicationIntent.Stage.RELEASE && product.status()==IncidentApplicationProduct.Status.REVIEW) {
                    var decision=decisions.find(tenant,product.decisionPointId()).orElseThrow(IncidentApplicationOrder::invalid);
                    if (decision.status()==DecisionPointStatus.OPEN) return StepResult.stay();
                    if (decision.status()!=DecisionPointStatus.APPROVED) {
                        ledger.stop(tenant,session,product.version(),"INCIDENT_APPLICATION_REVIEW_REJECTED",clock.instant());
                        if(!ledger.find(tenant,session).orElseThrow(IncidentApplicationOrder::invalid).terminal()) return StepResult.stay();
                        return StepResult.fail(new IllegalStateException("INCIDENT_APPLICATION_REVIEW_REJECTED"));
                    }
                }
                boolean ready=stage==IncidentApplicationIntent.Stage.BUILD && product.status()==IncidentApplicationProduct.Status.ACCEPTED
                        || stage==IncidentApplicationIntent.Stage.VERIFY && product.status()==IncidentApplicationProduct.Status.BUILT
                        || stage==IncidentApplicationIntent.Stage.RELEASE && product.status()==IncidentApplicationProduct.Status.REVIEW;
                if (ready) {
                    var submitted=launcher.stage(product,stage);
                    if(submitted.status()!=io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus.ACCEPTED
                            && submitted.status()!=io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus.SUCCEEDED) {
                        // Do not turn a denied/stale owner into an endless proposal loop.
                        var latest=ledger.find(tenant,session).orElseThrow(IncidentApplicationOrder::invalid);
                        if(latest.version()==product.version()) ledger.stop(tenant,session,product.version(),"INCIDENT_APPLICATION_STAGE_NOT_ACCEPTED",clock.instant());
                    }
                }
                return StepResult.stay();
            }
        };
    }
}
