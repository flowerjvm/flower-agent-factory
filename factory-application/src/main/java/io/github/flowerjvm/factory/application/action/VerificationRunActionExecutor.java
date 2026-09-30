package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.verification.VerificationAttemptTokens;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchTransaction;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionDispatch;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.DeferredActionExecutor;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/** Atomically records a verifier intent and parks the owning Action; it never runs the sandbox inline. */
public final class VerificationRunActionExecutor implements DeferredActionExecutor {
    private final VerificationDispatchTransaction dispatchTransaction;
    private final Clock clock;

    public VerificationRunActionExecutor(VerificationDispatchTransaction dispatchTransaction, Clock clock) {
        this.dispatchTransaction = Objects.requireNonNull(dispatchTransaction, "dispatchTransaction");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ActionDefinition definition() {
        return VerificationRunAction.definition();
    }

    @Override
    public ActionDispatch.Awaiting dispatchDeferred(ActionExecutionContext context) {
        VerificationRunInput input = VerificationRunInput.from(context.input());
        var tenantId = new io.github.flowerjvm.factory.contracts.ids.TenantId(
                context.executionContext().tenantId());
        String tokenHash = VerificationAttemptTokens.hash(context.attemptToken());
        var intent = dispatchTransaction.prepare(
                tenantId,
                input,
                context.executionContext().runId(),
                tokenHash,
                clock.instant());
        return ActionDispatch.awaiting(
                intent.operationId(),
                intent.deadlineAt(),
                Map.of(
                        "dispatchMode", "durable-verification-intent",
                        VerificationRunAction.VERIFICATION_RUN_ID, input.verificationRunId().value()));
    }
}
