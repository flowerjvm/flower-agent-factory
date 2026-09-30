package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseAuthorityException;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseAuthorityVerifier;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionDecision;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionGuard;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import java.util.Objects;

/** Rechecks the complete approved assembly graph immediately before release dispatch. */
public final class ReferenceAssemblyReleasePreExecutionGuard implements PreExecutionGuard {
    private final ReferenceAssemblyReleaseAuthorityVerifier authority;

    public ReferenceAssemblyReleasePreExecutionGuard(
            ReferenceAssemblyReleaseAuthorityVerifier authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    @Override
    public PreExecutionDecision check(
            ActionProposal proposal,
            ActionDefinition definition,
            ExecutionContext context,
            PolicyDecision policyDecision) {
        if (!ReferenceAssemblyReleaseAction.ACTION_ID.equals(proposal.actionId())
                || !ReferenceAssemblyReleaseAction.ACTION_ID.equals(definition.actionId())) {
            return deny("guard only accepts Reference Assembly release Actions");
        }
        try {
            ReferenceAssemblyReleaseInput input =
                    ReferenceAssemblyReleaseInput.from(proposal.input());
            TenantId tenantId = new TenantId(context.tenantId());
            if (!ReferenceAssemblyReleaseAction.RESOURCE_TYPE.equals(
                            context.metadata().get("resource.type"))
                    || !input.referenceAssemblyId().value().equals(
                            context.metadata().get("resource.id"))) {
                return deny("trusted release resource does not match");
            }
            authority.verify(tenantId, input);
            return PreExecutionDecision.allow();
        } catch (ReferenceAssemblyReleaseAuthorityException invalidAuthority) {
            return deny("exact release authority no longer holds");
        } catch (RuntimeException invalidInputOrScope) {
            return deny("release scope or input is invalid");
        }
    }

    private static PreExecutionDecision deny(String reason) {
        return PreExecutionDecision.deny(
                ReferenceAssemblyReleaseAuthorityException.AUTHORITY_INVALID, reason);
    }
}
