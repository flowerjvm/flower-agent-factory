package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.application.build.BuildSession;
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

/** Factory-owned proposals only; all preparation mutations remain in the registered executor. */
public final class ActionBackedAgentPackProductionLauncher {
    private final ActionRuntime runtime;
    private final Clock clock;
    private final Supplier<String> lifecycleIds;
    public ActionBackedAgentPackProductionLauncher(ActionRuntime runtime, Clock clock) {
        this(runtime, clock, () -> UUID.randomUUID().toString());
    }
    public ActionBackedAgentPackProductionLauncher(ActionRuntime runtime, Clock clock, Supplier<String> lifecycleIds) {
        this.runtime = Objects.requireNonNull(runtime, "runtime"); this.clock = Objects.requireNonNull(clock, "clock");
        this.lifecycleIds = Objects.requireNonNull(lifecycleIds, "lifecycleIds");
    }

    public ActionExecutionResult prepare(BuildSession session) {
        Objects.requireNonNull(session, "session");
        AgentPackProductionAuthority.requireLaunchable(session, clock.instant());
        var input = new AgentPackProductionInput(session.buildSessionId(), session.version());
        var proposal = ActionProposal.builder(AgentPackProductionAction.ACTION_ID)
                .proposalId("production-proposal-" + lifecycleId())
                .requestChannel(ActionRequestChannel.INTERNAL).proposerType(ActionProposerType.SERVICE)
                .requesterId(AgentPackProductionAction.REQUESTER_ID).reason("Prepare the current bounded production phase")
                .input(input.toMap()).idempotencyKey(AgentPackProductionIdempotencyKeys.derive(session)).build();
        var context = new ExecutionContext(session.tenantId().value(), AgentPackProductionAction.REQUESTER_ID,
                lifecycleId(), AgentPackProductionIdempotencyKeys.trace(session), Map.of(
                        "actor.permissions", Set.of(AgentPackProductionAction.PERMISSION),
                        "resource.type", AgentPackProductionAction.RESOURCE_TYPE,
                        "resource.id", session.buildSessionId().value()));
        return Objects.requireNonNull(runtime.handle(proposal, context), "production Action result");
    }

    private String lifecycleId() {
        String value = lifecycleIds.get();
        if (value == null || value.isBlank() || value.length() > 64 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("production lifecycle ID must be bounded non-control text");
        }
        return value;
    }
}
