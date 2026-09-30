package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Set;

/** A missing row never supplies authority: the independently trusted creation target does. */
final class AgentPackProductionIntakeAuthority {
    private static final Set<String> CONTEXT_KEYS = Set.of("actor.permissions", "resource.type", "resource.id", "resource.projectId");
    private AgentPackProductionIntakeAuthority() {}

    static AgentPackProductionIntakeInput current(BuildSessionRepository sessions, ActionProposal proposal,
                                                   ExecutionContext context, Instant now) {
        var input = scopedInput(sessions, proposal, context);
        input.requireLiveAt(now);
        return input;
    }

    static AgentPackProductionIntakeInput scopedInput(BuildSessionRepository sessions, ActionProposal proposal,
                                                       ExecutionContext context) {
        require(AgentPackProductionIntakeAction.ACTION_ID.equals(proposal.actionId())
                        && proposal.requestChannel() == ActionRequestChannel.INTERNAL
                        && proposal.proposerType() == ActionProposerType.SERVICE
                        && AgentPackProductionIntakeAction.REQUESTER_ID.equals(proposal.requesterId())
                        && AgentPackProductionIntakeAction.REQUESTER_ID.equals(context.userId())
                        && context.runId() != null && !context.runId().isBlank() && context.runId().length() <= 64
                        && !context.runId().chars().anyMatch(Character::isISOControl)
                        && context.metadata().keySet().equals(CONTEXT_KEYS), "intake owner context is not canonical");
        AgentPackProductionIntakeInput.boundedText(context.tenantId(), "tenant");
        AgentPackProductionIntakeInput.boundedText(proposal.idempotencyKey(), "requestKey");
        Object permissions = context.metadata().get("actor.permissions");
        require(permissions instanceof Collection<?> values && values.size() == 1
                && values.contains(AgentPackProductionIntakeAction.PERMISSION), "intake permission is missing");
        var input = AgentPackProductionIntakeInput.from(proposal.input());
        requireTarget(input, context);
        TenantId tenant = new TenantId(context.tenantId());
        sessions.find(tenant, input.buildSessionId()).ifPresent(existing -> requireExact(existing, tenant,
                context.userId(), proposal.idempotencyKey(), input));
        return input;
    }

    static void requireExact(BuildSession existing, TenantId tenant, String createdBy,
                             String requestKey, AgentPackProductionIntakeInput input) {
        require(existing.tenantId().equals(tenant) && existing.buildSessionId().equals(input.buildSessionId())
                        && existing.projectId().equals(input.projectId()) && existing.productLineId().equals(ProductLineId.AGENT_PACK)
                        && existing.requestIdempotencyKey().equals(requestKey) && existing.createdBy().equals(createdBy)
                        && existing.deadlineAt().equals(input.deadlineAt()) && existing.maxRepairRounds() == input.maxRepairRounds(),
                "existing order does not match the immutable intake identity");
    }

    static void requireTarget(AgentPackProductionIntakeInput input, ExecutionContext context) {
        require(AgentPackProductionIntakeAction.RESOURCE_TYPE.equals(context.metadata().get("resource.type"))
                        && input.buildSessionId().value().equals(context.metadata().get("resource.id"))
                        && input.projectId().value().equals(context.metadata().get("resource.projectId")),
                "trusted creation target does not match intake");
    }

    static String hash(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII)); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void require(boolean allowed, String message) {
        if (!allowed) throw new IllegalArgumentException(message);
    }
}
