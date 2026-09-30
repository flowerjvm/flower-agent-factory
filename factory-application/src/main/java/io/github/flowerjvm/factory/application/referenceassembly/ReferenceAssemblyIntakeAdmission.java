package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import java.time.Clock;
import java.util.Objects;

/** Read-only current scope and component metadata checks shared by all registered Action controls. */
public final class ReferenceAssemblyIntakeAdmission {
    private final ReferenceAssemblyIntakeService service;
    private final Clock clock;
    public ReferenceAssemblyIntakeAdmission(ReferenceAssemblyIntakeService service, Clock clock) {
        this.service = Objects.requireNonNull(service, "service"); this.clock = Objects.requireNonNull(clock, "clock");
    }
    public ReferenceAssemblyIntakeInput current(ActionProposal proposal, ExecutionContext context) {
        var input = scopedInput(proposal, context);
        service.checkCurrent(new TenantId(context.tenantId()), context.userId(), proposal.idempotencyKey(), input, clock.instant());
        return input;
    }

    /** Stable host-scoped identity for owner-aware reserve/complete/release; mutable checks stay in policy/guard. */
    ReferenceAssemblyIntakeInput scopedInput(ActionProposal proposal, ExecutionContext context) {
        return ReferenceAssemblyIntakeAuthority.input(proposal, context);
    }
}
