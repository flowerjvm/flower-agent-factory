package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/** Registered intake executor only; this boundary commits inputs but never submits a Flow or Worker. */
public final class AgentPackProductionIntakeService {
    private final AgentPackProductionService service;
    private final AgentPackProductionIntakeInputs inputs;
    private final Clock clock;

    public AgentPackProductionIntakeService(AgentPackProductionService service, AgentPackProductionIntakeInputs inputs, Clock clock) {
        this.service = Objects.requireNonNull(service, "service");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BuildSession accept(TenantId tenant, String createdBy, String requestKey, AgentPackProductionIntakeInput input) {
        Objects.requireNonNull(tenant, "tenant"); Objects.requireNonNull(input, "input");
        AgentPackProductionIntakeInput.boundedText(tenant.value(), "tenant");
        AgentPackProductionIntakeInput.boundedText(requestKey, "requestKey");
        if (!AgentPackProductionIntakeAction.REQUESTER_ID.equals(createdBy)) {
            throw new IllegalArgumentException("intake requires the Factory service owner");
        }
        input.requireLiveAt(clock.instant());
        var bundle = Objects.requireNonNull(inputs.prepare(tenant, input.buildSessionId()), "intake inputs");
        var plan = bundle.plan();
        if (!tenant.equals(plan.tenantId()) || !input.buildSessionId().equals(plan.buildSessionId())) {
            throw new IllegalArgumentException("production input owner does not match intake");
        }
        // Read-only assembly may take time: create and recheck the deadline after it finishes.
        var observed = clock.instant();
        input.requireLiveAt(observed);
        var now = observed.truncatedTo(ChronoUnit.MICROS);
        var pristine = new BuildSession(input.buildSessionId(), tenant, input.projectId(), ProductLineId.AGENT_PACK,
                requestKey, createdBy, BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER,
                plan.requirements().reference(), plan.requirements().hash(), Optional.of(plan.manager().bindingId()),
                Optional.of(plan.coding().bindingId()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                0, input.maxRepairRounds(), now, input.deadlineAt(), Optional.empty(), Optional.empty(), Optional.empty(),
                0, now, now);
        return service.accept(pristine, plan, bundle.artifacts());
    }
}
