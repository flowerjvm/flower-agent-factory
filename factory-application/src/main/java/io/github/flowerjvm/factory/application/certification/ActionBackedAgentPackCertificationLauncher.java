package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.application.action.CertificationIssueAction;
import io.github.flowerjvm.factory.application.action.CertificationIssueIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.CertificationIssueInput;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Proposes the governed Certification Action for one exact REQUESTED Agent Pack lock.
 *
 * <p>Every transport retry gets fresh proposal/run lifecycle ids. The exact Certification input
 * and idempotency key remain stable, so duplicate handling converges on the original governed
 * Action owner rather than manufacturing a second logical issuance.
 */
public final class ActionBackedAgentPackCertificationLauncher {
    public static final String REQUESTER_ID = "factory-certifier";

    private final ActionRuntime actionRuntime;
    private final Supplier<String> lifecycleIds;

    public ActionBackedAgentPackCertificationLauncher(ActionRuntime actionRuntime) {
        this(actionRuntime, () -> UUID.randomUUID().toString());
    }

    public ActionBackedAgentPackCertificationLauncher(
            ActionRuntime actionRuntime, Supplier<String> lifecycleIds) {
        this.actionRuntime = Objects.requireNonNull(actionRuntime, "actionRuntime");
        this.lifecycleIds = Objects.requireNonNull(lifecycleIds, "lifecycleIds");
    }

    /** Performs one bounded Action Runtime proposal; issuance itself remains deferred. */
    public ActionExecutionResult ensureProposed(
            BuildSession session,
            Certification certification,
            io.github.flowerjvm.flower.core.context.ExecutionContext flowIdentity,
            Instant proposedAt) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(certification, "certification");
        Objects.requireNonNull(flowIdentity, "flowIdentity");
        Objects.requireNonNull(proposedAt, "proposedAt");
        requireLaunchable(session, certification, flowIdentity, proposedAt);

        CertificationIssueInput input = new CertificationIssueInput(
                certification.certificationId(),
                certification.inputLockArtifact().hash(),
                certification.version());
        String proposalLifecycleId = lifecycleId(96, "proposal lifecycle id");
        String actionRunId = lifecycleId(64, "Action run id");
        ActionProposal proposal = ActionProposal.builder(CertificationIssueAction.ACTION_ID)
                .proposalId("certification-proposal-" + proposalLifecycleId)
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId(REQUESTER_ID)
                .reason("Issue the exact trusted Agent Pack Certification")
                .input(input.toMap())
                .idempotencyKey(CertificationIssueIdempotencyKeys.derive(certification))
                .build();
        var actionContext = new io.github.flowerjvm.flower.action.runtime.ExecutionContext(
                session.tenantId().value(),
                REQUESTER_ID,
                actionRunId,
                requireText(flowIdentity.traceIdOrNull(), "flow traceId", 256),
                Map.of(
                        "actor.permissions", Set.of(CertificationIssueAction.PERMISSION),
                        "resource.type", CertificationIssueAction.RESOURCE_TYPE,
                        "resource.id", certification.certificationId().value()));
        return Objects.requireNonNull(
                actionRuntime.handle(proposal, actionContext), "Certification Action result");
    }

    private String lifecycleId(int maximumLength, String name) {
        return requireText(lifecycleIds.get(), name, maximumLength);
    }

    private static void requireLaunchable(
            BuildSession session,
            Certification certification,
            io.github.flowerjvm.flower.core.context.ExecutionContext identity,
            Instant proposedAt) {
        var lock = certification.inputLock();
        if (!ProductLineId.AGENT_PACK.equals(session.productLineId())
                || session.status() != BuildSessionStatus.CERTIFYING
                || session.currentPhase() != BuildSessionPhase.CERTIFY
                || session.currentCandidateId().filter(lock.candidateId()::equals).isEmpty()
                || session.currentCandidateHash().filter(lock.candidateHash()::equals).isEmpty()
                || session.currentCertificationId().isPresent()
                || session.cancellationRequestedAt().isPresent()
                || certification.status() != CertificationStatus.REQUESTED
                || !lock.tenantId().equals(session.tenantId())
                || !lock.buildSessionId().equals(session.buildSessionId())
                || !ProductLineId.AGENT_PACK.equals(lock.productLineId())
                || lock.artifactType() != CertifiedArtifactType.AGENT_PACK) {
            throw new IllegalArgumentException(
                    "Certification Action requires the exact live Agent Pack REQUESTED authority");
        }
        if (proposedAt.isBefore(session.updatedAt()) || !proposedAt.isBefore(session.deadlineAt())) {
            throw new IllegalArgumentException(
                    "Certification Action proposal is stale or at/after the persisted deadline");
        }
        if (!session.tenantId().value().equals(identity.tenantIdOrNull())
                || !session.createdBy().equals(identity.userIdOrNull())
                || !session.buildSessionId().value().equals(identity.sessionIdOrNull())
                || !session.projectId().value().equals(identity.correlationIdOrNull())
                || identity.runIdOrNull() == null
                || identity.runIdOrNull().isBlank()
                || identity.traceIdOrNull() == null
                || identity.traceIdOrNull().isBlank()) {
            throw new IllegalArgumentException(
                    "Flower execution identity does not match the durable BuildSession authority");
        }
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
        }
        return value;
    }
}
