package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.contracts.ids.DecisionId;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.action.*;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Only this registered executor records the operator's decision. It never submits Flow or production work. */
public final class DecisionRecordActionExecutor implements SynchronousActionExecutor {
    private final DecisionRecordingService service;
    private final DecisionRecordAdmission admission;
    public DecisionRecordActionExecutor(DecisionRecordingService service, DecisionRecordAdmission admission) {
        this.service = Objects.requireNonNull(service); this.admission = Objects.requireNonNull(admission);
    }
    @Override public ActionDefinition definition() { return DecisionRecordAction.definition(); }
    @Override public ActionExecutionResult execute(ActionExecutionContext context) {
        try {
            var entry = admission.current(context.proposal(), context.executionContext());
            var recorded = service.record(entry.request(new DecisionId("decision-" + UUID.randomUUID())), entry.requestContext());
            if (recorded.disposition() == DecisionRecordingDisposition.CONFLICT) return ActionExecutionResult.permanentFailure(
                    "DECISION_RECORD_CONFLICT", "Decision conflicts with the current immutable record");
            var decision = recorded.recordedDecision().orElseThrow();
            return new ActionExecutionResult(ActionExecutionStatus.SUCCEEDED, "DECISION_RECORDED", "Exact human decision recorded",
                    Map.of("decisionId", decision.decisionId().value(), "decisionPointId", decision.decisionPointId().value(),
                            "subjectHash", decision.subjectHash().sha256(), "outcome", decision.decision().name()), RetryDisposition.NEVER);
        } catch (RuntimeException uncertain) {
            return ActionExecutionResult.manualReviewFailure("DECISION_RECORD_UNCERTAIN", "Decision recording requires canonical readback");
        }
    }
}
