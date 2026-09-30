package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Host-only entry: trusted target is independently supplied, never promoted from the payload. */
public final class ActionBackedAgentPackProductionIntakeLauncher {
    private final ActionRuntime runtime;
    private final Clock clock;
    private final Supplier<String> lifecycleIds;
    public ActionBackedAgentPackProductionIntakeLauncher(ActionRuntime runtime, Clock clock) {
        this(runtime, clock, () -> UUID.randomUUID().toString());
    }
    public ActionBackedAgentPackProductionIntakeLauncher(ActionRuntime runtime, Clock clock, Supplier<String> lifecycleIds) {
        this.runtime = Objects.requireNonNull(runtime, "runtime"); this.clock = Objects.requireNonNull(clock, "clock");
        this.lifecycleIds = Objects.requireNonNull(lifecycleIds, "lifecycleIds");
    }

    /** Caller may submit the Flow only after the successful Action and its acceptance transaction have completed. */
    public ActionExecutionResult submit(TenantId trustedTenant, BuildSessionId trustedSession, ProjectId trustedProject,
                                        String requestKey, AgentPackProductionIntakeInput input) {
        Objects.requireNonNull(trustedTenant, "trustedTenant"); Objects.requireNonNull(trustedSession, "trustedSession");
        Objects.requireNonNull(trustedProject, "trustedProject"); Objects.requireNonNull(input, "input");
        AgentPackProductionIntakeInput.boundedText(trustedTenant.value(), "tenant");
        AgentPackProductionIntakeInput.boundedText(requestKey, "requestKey");
        if (!trustedSession.equals(input.buildSessionId()) || !trustedProject.equals(input.projectId())) {
            throw new IllegalArgumentException("trusted creation target does not match intake");
        }
        input.requireLiveAt(clock.instant());
        var proposal = ActionProposal.builder(AgentPackProductionIntakeAction.ACTION_ID)
                .proposalId("intake-proposal-" + lifecycleId()).requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE).requesterId(AgentPackProductionIntakeAction.REQUESTER_ID)
                .reason("Accept a bounded immutable Agent Pack production order").input(input.toMap()).idempotencyKey(requestKey).build();
        var context = new ExecutionContext(trustedTenant.value(), AgentPackProductionIntakeAction.REQUESTER_ID,
                lifecycleId(), "intake-" + AgentPackProductionIntakeAuthority.hash(trustedTenant.value(),
                        trustedSession.value(), trustedProject.value(), requestKey),
                Map.of("actor.permissions", Set.of(AgentPackProductionIntakeAction.PERMISSION),
                        "resource.type", AgentPackProductionIntakeAction.RESOURCE_TYPE,
                        "resource.id", trustedSession.value(), "resource.projectId", trustedProject.value()));
        return Objects.requireNonNull(runtime.handle(proposal, context), "intake Action result");
    }

    private String lifecycleId() {
        String id = lifecycleIds.get();
        if (id == null || id.isBlank() || id.length() > 64 || id.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("intake lifecycle ID must be bounded non-control text");
        }
        return AgentPackProductionIntakeInput.boundedText(id, "intake lifecycle ID");
    }
}
