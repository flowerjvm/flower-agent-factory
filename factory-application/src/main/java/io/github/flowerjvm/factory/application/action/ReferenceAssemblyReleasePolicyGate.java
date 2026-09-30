package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.referenceassembly.ActionBackedReferenceAssemblyReleaseLauncher;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssembly;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyRepository;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyStatus;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.policy.DefaultPolicyGate;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/** Authorizes an exact Reference Assembly release before duplicate lookup. */
public final class ReferenceAssemblyReleasePolicyGate implements PolicyGate {
    static final String DENY_MESSAGE = "Reference Assembly release is not authorized";
    private static final Set<String> CANONICAL_CONTEXT_KEYS = Set.of(
            "actor.permissions", "resource.type", "resource.id");

    private final ReferenceAssemblyRepository assemblies;
    private final PolicyGate baseline;

    public ReferenceAssemblyReleasePolicyGate(ReferenceAssemblyRepository assemblies) {
        this(assemblies, new DefaultPolicyGate());
    }

    ReferenceAssemblyReleasePolicyGate(
            ReferenceAssemblyRepository assemblies, PolicyGate baseline) {
        this.assemblies = Objects.requireNonNull(assemblies, "assemblies");
        this.baseline = Objects.requireNonNull(baseline, "baseline");
    }

    @Override
    public PolicyDecision evaluate(
            ActionProposal proposal,
            ActionDefinition definition,
            ExecutionContext context) {
        if (!ReferenceAssemblyReleaseAction.ACTION_ID.equals(proposal.actionId())
                || !ReferenceAssemblyReleaseAction.ACTION_ID.equals(definition.actionId())
                || !ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID.equals(
                        proposal.requesterId())) {
            return deny();
        }
        PolicyDecision baselineDecision = baseline.evaluate(proposal, definition, context);
        if (!baselineDecision.allowedToExecuteNow()) {
            return baselineDecision;
        }
        if (!hasCanonicalContext(context)
                || !hasExactPermission(
                        context.metadata().get("actor.permissions"),
                        ReferenceAssemblyReleaseAction.PERMISSION)) {
            return deny();
        }

        ReferenceAssemblyReleaseInput input;
        try {
            input = ReferenceAssemblyReleaseInput.from(proposal.input());
        } catch (IllegalArgumentException invalid) {
            return deny();
        }
        if (!ReferenceAssemblyReleaseAction.RESOURCE_TYPE.equals(
                        context.metadata().get("resource.type"))
                || !input.referenceAssemblyId().value().equals(
                        context.metadata().get("resource.id"))) {
            return deny();
        }

        ReferenceAssembly assembly;
        try {
            TenantId tenantId = new TenantId(context.tenantId());
            assembly = assemblies.find(tenantId, input.referenceAssemblyId()).orElse(null);
            if (!hasExactTrustedLocks(assembly, tenantId, input)) {
                return deny();
            }
        } catch (RuntimeException unavailableOrInvalid) {
            return deny();
        }

        boolean initial = assembly.status() == ReferenceAssemblyStatus.INSPECTED
                && assembly.releaseActionRunId().isEmpty()
                && input.expectedReferenceAssemblyVersion() == assembly.version();
        boolean duplicate = isOriginalOwnedReplay(assembly, input);
        if (!initial && !duplicate) {
            return deny();
        }

        try {
            if (!ReferenceAssemblyReleaseIdempotencyKeys
                    .derive(assembly, input.expectedReferenceAssemblyVersion())
                    .equals(proposal.idempotencyKey())) {
                return deny();
            }
        } catch (RuntimeException invalidLock) {
            return deny();
        }
        return PolicyDecision.allow();
    }

    private static boolean hasExactTrustedLocks(
            ReferenceAssembly assembly,
            TenantId tenantId,
            ReferenceAssemblyReleaseInput input) {
        return assembly != null
                && assembly.tenantId().equals(tenantId)
                && assembly.referenceAssemblyId().equals(input.referenceAssemblyId())
                && assembly.assemblyManifest()
                        .map(lock -> lock.hash().equals(input.assemblyManifestHash()))
                        .orElse(false)
                && assembly.inspectionReport()
                        .map(lock -> lock.hash().equals(input.inspectionReportHash()))
                        .orElse(false)
                && assembly.releaseDecisionPointId()
                        .filter(input.releaseDecisionPointId()::equals)
                        .isPresent()
                && assembly.releaseSubjectHash()
                        .filter(input.releaseSubjectHash()::equals)
                        .isPresent();
    }

    private static boolean isOriginalOwnedReplay(
            ReferenceAssembly assembly, ReferenceAssemblyReleaseInput input) {
        long expectedVersion = input.expectedReferenceAssemblyVersion();
        if (expectedVersion < 0 || expectedVersion >= assembly.version()) {
            return false;
        }
        if (assembly.status() == ReferenceAssemblyStatus.INSPECTED
                && assembly.releaseActionRunId().isPresent()) {
            return expectedVersion == assembly.version() - 1;
        }
        if (assembly.status() == ReferenceAssemblyStatus.RELEASED
                && assembly.releaseActionRunId().isPresent()
                && assembly.releaseManifest().isPresent()) {
            return assembly.version() >= 2 && expectedVersion == assembly.version() - 2;
        }
        return false;
    }

    private static boolean hasCanonicalContext(ExecutionContext context) {
        return context.tenantId() != null
                && !context.tenantId().isBlank()
                && ActionBackedReferenceAssemblyReleaseLauncher.REQUESTER_ID.equals(
                        context.userId())
                && context.traceId() != null
                && !context.traceId().isBlank()
                && context.metadata().keySet().equals(CANONICAL_CONTEXT_KEYS);
    }

    private static boolean hasExactPermission(Object value, String required) {
        return value instanceof Collection<?> permissions
                && permissions.size() == 1
                && permissions.stream().allMatch(required::equals);
    }

    private static PolicyDecision deny() {
        return PolicyDecision.deny(DENY_MESSAGE);
    }
}
