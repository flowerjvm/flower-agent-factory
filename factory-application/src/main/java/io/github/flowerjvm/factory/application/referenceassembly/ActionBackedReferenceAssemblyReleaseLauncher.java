package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseAction;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseInput;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
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

/** Proposes the governed Action for one exact approved Reference Assembly release. */
public final class ActionBackedReferenceAssemblyReleaseLauncher
        implements ReferenceAssemblyReleaseLauncher {
    public static final String REQUESTER_ID = "factory-reference-assembly-releaser";

    private final ActionRuntime actionRuntime;
    private final Supplier<String> lifecycleIds;

    public ActionBackedReferenceAssemblyReleaseLauncher(ActionRuntime actionRuntime) {
        this(actionRuntime, () -> UUID.randomUUID().toString());
    }

    public ActionBackedReferenceAssemblyReleaseLauncher(
            ActionRuntime actionRuntime, Supplier<String> lifecycleIds) {
        this.actionRuntime = Objects.requireNonNull(actionRuntime, "actionRuntime");
        this.lifecycleIds = Objects.requireNonNull(lifecycleIds, "lifecycleIds");
    }

    /** Performs one bounded proposal; packaging remains deferred behind the durable intent. */
    @Override
    public ActionExecutionResult ensureProposed(
            BuildSession session,
            ReferenceAssembly assembly,
            io.github.flowerjvm.flower.core.context.ExecutionContext flowIdentity,
            Instant proposedAt) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(flowIdentity, "flowIdentity");
        Objects.requireNonNull(proposedAt, "proposedAt");
        requireLaunchable(session, assembly, flowIdentity, proposedAt);

        ReferenceAssemblyReleaseInput input = new ReferenceAssemblyReleaseInput(
                assembly.referenceAssemblyId(),
                assembly.assemblyManifest().orElseThrow().hash(),
                assembly.inspectionReport().orElseThrow().hash(),
                assembly.releaseDecisionPointId().orElseThrow(),
                assembly.releaseSubjectHash().orElseThrow(),
                assembly.version());
        String proposalLifecycleId = lifecycleId(96, "proposal lifecycle id");
        String actionRunId = lifecycleId(64, "Action run id");
        ActionProposal proposal = ActionProposal.builder(ReferenceAssemblyReleaseAction.ACTION_ID)
                .proposalId("reference-assembly-release-proposal-" + proposalLifecycleId)
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId(REQUESTER_ID)
                .reason("Package the exact approved Reference Assembly")
                .input(input.toMap())
                .idempotencyKey(ReferenceAssemblyReleaseIdempotencyKeys.derive(assembly))
                .build();
        var actionContext = new io.github.flowerjvm.flower.action.runtime.ExecutionContext(
                session.tenantId().value(),
                REQUESTER_ID,
                actionRunId,
                requireText(flowIdentity.traceIdOrNull(), "flow traceId", 256),
                Map.of(
                        "actor.permissions",
                        Set.of(ReferenceAssemblyReleaseAction.PERMISSION),
                        "resource.type",
                        ReferenceAssemblyReleaseAction.RESOURCE_TYPE,
                        "resource.id",
                        assembly.referenceAssemblyId().value()));
        return Objects.requireNonNull(
                actionRuntime.handle(proposal, actionContext),
                "Reference Assembly release Action result");
    }

    private String lifecycleId(int maximumLength, String name) {
        return requireText(lifecycleIds.get(), name, maximumLength);
    }

    private static void requireLaunchable(
            BuildSession session,
            ReferenceAssembly assembly,
            io.github.flowerjvm.flower.core.context.ExecutionContext identity,
            Instant proposedAt) {
        if (!ProductLineId.REFERENCE_ASSEMBLY.equals(session.productLineId())
                || session.status() != BuildSessionStatus.RUNNING
                || session.currentPhase() != BuildSessionPhase.PACKAGE_RELEASE
                || session.cancellationRequestedAt().isPresent()
                || !session.tenantId().equals(assembly.tenantId())
                || !session.buildSessionId().equals(assembly.buildSessionId())
                || assembly.status() != ReferenceAssemblyStatus.INSPECTED
                || assembly.assemblyManifest().isEmpty()
                || assembly.inspectionReport().isEmpty()
                || assembly.releaseDecisionPointId().isEmpty()
                || assembly.releaseSubjectHash().isEmpty()
                || assembly.releaseActionRunId().isPresent()
                || assembly.releaseManifest().isPresent()) {
            throw new IllegalArgumentException(
                    "release Action requires the exact live approved Reference Assembly authority");
        }
        if (proposedAt.isBefore(session.updatedAt())
                || proposedAt.isBefore(assembly.updatedAt())
                || !proposedAt.isBefore(session.deadlineAt())) {
            throw new IllegalArgumentException(
                    "release Action proposal is stale or at/after the persisted deadline");
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
