package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import java.util.Collection;
import java.util.Set;

/** Missing resources confer no authority. The host supplies an independent creation target. */
final class ReferenceAssemblyIntakeAuthority {
    private static final Set<String> KEYS = Set.of("actor.permissions", "resource.type", "resource.id", "resource.projectId");
    private ReferenceAssemblyIntakeAuthority() {}

    static ReferenceAssemblyIntakeInput input(ActionProposal proposal, ExecutionContext context) {
        require(ReferenceAssemblyIntakeAction.ACTION_ID.equals(proposal.actionId())
                && proposal.requestChannel() == ActionRequestChannel.INTERNAL
                && proposal.proposerType() == ActionProposerType.SERVICE
                && ReferenceAssemblyIntakeAction.REQUESTER_ID.equals(proposal.requesterId())
                && ReferenceAssemblyIntakeAction.REQUESTER_ID.equals(context.userId())
                && context.metadata().keySet().equals(KEYS), "intake owner is not canonical");
        ReferenceAssemblyIntakeInput.boundedText(context.runId(), "runId", 64);
        ReferenceAssemblyIntakeInput.boundedText(context.tenantId(), "tenant", 128);
        ReferenceAssemblyIntakeInput.boundedText(proposal.idempotencyKey(), "requestKey", 255);
        Object grants = context.metadata().get("actor.permissions");
        require(grants instanceof Collection<?> values && values.size() == 1 && values.contains(ReferenceAssemblyIntakeAction.PERMISSION),
                "intake permission is missing");
        var input = ReferenceAssemblyIntakeInput.from(proposal.input());
        require(ReferenceAssemblyIntakeAction.RESOURCE_TYPE.equals(context.metadata().get("resource.type"))
                && input.buildSessionId().value().equals(context.metadata().get("resource.id"))
                && input.projectId().value().equals(context.metadata().get("resource.projectId")), "creation target differs");
        return input;
    }

    static String hash(String... fields) { return ReferenceAssemblyIntakeReceipt.hash(ReferenceAssemblyIntakeReceipt.fields(fields)).sha256(); }

    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
