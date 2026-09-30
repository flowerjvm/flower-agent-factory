package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.certification.CertificationAttemptTokens;
import io.github.flowerjvm.factory.application.certification.CertificationDispatchTransaction;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionDispatch;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.DeferredActionExecutor;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/** Persists a Certification intent and parks the Action; canonical issuance runs only in the runner. */
public final class CertificationIssueActionExecutor implements DeferredActionExecutor {
    private final CertificationDispatchTransaction dispatchTransaction;
    private final Clock clock;

    public CertificationIssueActionExecutor(
            CertificationDispatchTransaction dispatchTransaction, Clock clock) {
        this.dispatchTransaction = Objects.requireNonNull(dispatchTransaction, "dispatchTransaction");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ActionDefinition definition() {
        return CertificationIssueAction.definition();
    }

    @Override
    public ActionDispatch.Awaiting dispatchDeferred(ActionExecutionContext context) {
        CertificationIssueInput input = CertificationIssueInput.from(context.input());
        TenantId tenantId = new TenantId(context.executionContext().tenantId());
        var intent = dispatchTransaction.prepare(
                tenantId,
                input,
                context.executionContext().runId(),
                CertificationAttemptTokens.hash(context.attemptToken()),
                clock.instant());
        return ActionDispatch.awaiting(
                intent.operationId(),
                intent.deadlineAt(),
                Map.of(
                        "dispatchMode", "durable-certification-intent",
                        CertificationIssueAction.CERTIFICATION_ID,
                        input.certificationId().value()));
    }
}
