package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/** Shared read-only checks; neither policy nor guard can prepare work. */
final class AgentPackProductionAuthority {
    private static final Set<String> CONTEXT_KEYS = Set.of("actor.permissions", "resource.type", "resource.id");
    private AgentPackProductionAuthority() {}

    static BuildSession current(BuildSessionRepository sessions, ActionProposal proposal, ExecutionContext context, Instant now) {
        require(AgentPackProductionAction.ACTION_ID.equals(proposal.actionId())
                        && AgentPackProductionAction.REQUESTER_ID.equals(proposal.requesterId())
                        && AgentPackProductionAction.REQUESTER_ID.equals(context.userId())
                        && context.runId() != null && !context.runId().isBlank() && context.runId().length() <= 64
                        && context.metadata().keySet().equals(CONTEXT_KEYS),
                "production owner context is not canonical");
        Object permissions = context.metadata().get("actor.permissions");
        require(permissions instanceof Collection<?> values && values.size() == 1
                        && values.contains(AgentPackProductionAction.PERMISSION), "production permission is missing");
        AgentPackProductionInput input = AgentPackProductionInput.from(proposal.input());
        BuildSession session = resource(sessions, input, context);
        require(session.version() == input.expectedSessionVersion()
                        && AgentPackProductionIdempotencyKeys.derive(session).equals(proposal.idempotencyKey())
                        && AgentPackProductionIdempotencyKeys.trace(session).equals(context.traceId()),
                "production phase identity or expected version changed");
        requireLaunchable(session, now);
        return session;
    }

    static BuildSession resource(BuildSessionRepository sessions, AgentPackProductionInput input, ExecutionContext context) {
        require(AgentPackProductionAction.RESOURCE_TYPE.equals(context.metadata().get("resource.type"))
                        && input.buildSessionId().value().equals(context.metadata().get("resource.id")),
                "trusted production resource does not match the request");
        TenantId tenant = new TenantId(context.tenantId());
        BuildSession session = sessions.find(tenant, input.buildSessionId())
                .orElseThrow(() -> new IllegalArgumentException("production resource is unavailable"));
        require(session.tenantId().equals(tenant) && session.buildSessionId().equals(input.buildSessionId())
                        && session.productLineId().equals(ProductLineId.AGENT_PACK),
                "canonical production resource does not match the trusted scope");
        return session;
    }

    static void requireLaunchable(BuildSession session, Instant now) {
        boolean coding = (session.currentPhase() == BuildSessionPhase.DESIGN_AGENT
                        || session.currentPhase() == BuildSessionPhase.GENERATE_CANDIDATE)
                && (session.status() == BuildSessionStatus.RUNNING || session.status() == BuildSessionStatus.REPAIRING);
        boolean review = session.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW
                && session.status() == BuildSessionStatus.WAITING_RELEASE_REVIEW
                && session.currentCandidateId().isPresent() && session.currentCandidateHash().isPresent();
        require(session.productLineId().equals(ProductLineId.AGENT_PACK) && (coding || review)
                        && session.selectedCodingWorkerBinding().isPresent()
                        && session.cancellationRequestedAt().isEmpty() && session.currentCertificationId().isEmpty()
                        && session.terminalCode().isEmpty() && session.terminalMessage().isEmpty()
                        && !now.isBefore(session.updatedAt()) && now.isBefore(session.deadlineAt()),
                "production phase is unavailable, cancelled or outside its persisted deadline");
    }

    private static void require(boolean allowed, String reason) {
        if (!allowed) throw new IllegalArgumentException(reason);
    }
}
