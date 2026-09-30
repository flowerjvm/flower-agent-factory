package io.github.flowerjvm.factory.application.referenceassembly;

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

/** Host-only controlled entry; callers supply creation authority independently of the payload. */
public final class ActionBackedReferenceAssemblyIntakeLauncher {
    private final ActionRuntime runtime;
    private final Clock clock;
    private final Supplier<String> lifecycleIds;
    public ActionBackedReferenceAssemblyIntakeLauncher(ActionRuntime runtime, Clock clock) { this(runtime, clock, () -> UUID.randomUUID().toString()); }
    public ActionBackedReferenceAssemblyIntakeLauncher(ActionRuntime runtime, Clock clock, Supplier<String> lifecycleIds) {
        this.runtime = Objects.requireNonNull(runtime); this.clock = Objects.requireNonNull(clock); this.lifecycleIds = Objects.requireNonNull(lifecycleIds);
    }
    /** Submit the existing six-step Flow only after Action success and canonical post-commit readback. */
    public ActionExecutionResult submit(TenantId tenant, ProjectId project, BuildSessionId session, String requestKey, ReferenceAssemblyIntakeInput input) {
        Objects.requireNonNull(tenant); Objects.requireNonNull(project); Objects.requireNonNull(session); Objects.requireNonNull(input);
        ReferenceAssemblyIntakeInput.boundedText(tenant.value(), "tenant", 128);
        ReferenceAssemblyIntakeInput.boundedText(requestKey, "requestKey", 255);
        if (!project.equals(input.projectId()) || !session.equals(input.buildSessionId())) throw new IllegalArgumentException("trusted target differs from intake");
        input.requireLiveAt(clock.instant());
        var proposal = ActionProposal.builder(ReferenceAssemblyIntakeAction.ACTION_ID).proposalId("ra-intake-proposal-" + lifecycleId())
                .requestChannel(ActionRequestChannel.INTERNAL).proposerType(ActionProposerType.SERVICE)
                .requesterId(ReferenceAssemblyIntakeAction.REQUESTER_ID).idempotencyKey(requestKey).input(input.toMap())
                .reason("Accept one exact certified component for bounded Reference Assembly production").build();
        var context = new ExecutionContext(tenant.value(), ReferenceAssemblyIntakeAction.REQUESTER_ID, lifecycleId(),
                "ra-intake-" + ReferenceAssemblyIntakeAuthority.hash(tenant.value(), project.value(), session.value(), requestKey),
                Map.of("actor.permissions", Set.of(ReferenceAssemblyIntakeAction.PERMISSION), "resource.type", ReferenceAssemblyIntakeAction.RESOURCE_TYPE,
                        "resource.id", session.value(), "resource.projectId", project.value()));
        return Objects.requireNonNull(runtime.handle(proposal, context), "intake Action result");
    }
    private String lifecycleId() { return ReferenceAssemblyIntakeInput.boundedText(lifecycleIds.get(), "lifecycle ID", 64); }
}
