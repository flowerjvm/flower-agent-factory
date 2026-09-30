package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.flower.action.runtime.*;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Host-only local operator entry. It is never an AI approval generator or a public unauthenticated API. */
public final class ActionBackedDecisionRecordLauncher {
    private final ActionRuntime runtime;
    private final Clock clock;
    public ActionBackedDecisionRecordLauncher(ActionRuntime runtime, Clock clock) {
        this.runtime = Objects.requireNonNull(runtime); this.clock = Objects.requireNonNull(clock);
    }
    public ActionExecutionResult record(DecisionRecordAuthority authority, DecisionPointId trustedDecisionPointId,
            String requestKey, DecisionRecordInput input) {
        Objects.requireNonNull(authority); Objects.requireNonNull(input); Objects.requireNonNull(trustedDecisionPointId);
        DecisionRecordInput.text(requestKey, 255);
        if (!trustedDecisionPointId.equals(input.decisionPointId())) throw new IllegalArgumentException("DECISION_RESOURCE_MISMATCH");
        // A fresh request lifecycle never replaces the stable logical request key; no user timestamp is accepted.
        Objects.requireNonNull(clock.instant());
        var proposal = ActionProposal.builder(DecisionRecordAction.ACTION_ID).proposalId("decision-proposal-" + UUID.randomUUID())
                .requestChannel(ActionRequestChannel.CLI).proposerType(ActionProposerType.USER).requesterId(authority.principal())
                .reason("Record the operator's explicit exact-subject decision").input(input.toMap()).idempotencyKey(requestKey).build();
        var context = new ExecutionContext(authority.tenantId().value(), authority.principal(), UUID.randomUUID().toString(),
                "decision-" + DecisionRecordAdmission.hash(authority.tenantId().value(), authority.projectId().value(),
                        authority.principal(), trustedDecisionPointId.value(), requestKey),
                Map.of("actor.permissions", authority.permissions(), "actor.authoritySnapshotRef", authority.authoritySnapshotRef().value(),
                        "resource.type", DecisionRecordAction.RESOURCE_TYPE, "resource.id", trustedDecisionPointId.value(),
                        "resource.projectId", authority.projectId().value()));
        return Objects.requireNonNull(runtime.handle(proposal, context));
    }
}
