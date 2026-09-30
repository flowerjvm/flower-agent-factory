package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseAttemptTokens;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDeadlines;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchTransaction;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.action.ActionDispatch;
import io.github.flowerjvm.flower.action.runtime.action.ActionExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.DeferredActionExecutor;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

/** Persists a release intent and parks the Action; packaging runs only in the bounded runner. */
public final class ReferenceAssemblyReleaseActionExecutor implements DeferredActionExecutor {
    public static final String DISPATCH_MODE = "durable-reference-assembly-release-intent";

    private final ReferenceAssemblyReleaseDispatchTransaction dispatchTransaction;
    private final Clock clock;

    public ReferenceAssemblyReleaseActionExecutor(
            ReferenceAssemblyReleaseDispatchTransaction dispatchTransaction, Clock clock) {
        this.dispatchTransaction = Objects.requireNonNull(
                dispatchTransaction, "dispatchTransaction");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ActionDefinition definition() {
        return ReferenceAssemblyReleaseAction.definition();
    }

    @Override
    public ActionDispatch.Awaiting dispatchDeferred(ActionExecutionContext context) {
        ReferenceAssemblyReleaseInput input =
                ReferenceAssemblyReleaseInput.from(context.input());
        TenantId tenantId = new TenantId(context.executionContext().tenantId());
        var intent = dispatchTransaction.prepare(
                tenantId,
                input,
                context.executionContext().runId(),
                ReferenceAssemblyReleaseAttemptTokens.hash(context.attemptToken()),
                // The release transaction and intent require canonical JDBC microseconds.
                // Normalize this host-owned observation; never relax their strict time checks.
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        return ActionDispatch.awaiting(
                intent.operationId(),
                ReferenceAssemblyReleaseDeadlines.actionDueAt(intent.deadlineAt()),
                Map.of(
                        "dispatchMode",
                        DISPATCH_MODE,
                        ReferenceAssemblyReleaseAction.REFERENCE_ASSEMBLY_ID,
                        input.referenceAssemblyId().value()));
    }
}
