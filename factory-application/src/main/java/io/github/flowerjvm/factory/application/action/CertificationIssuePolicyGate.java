package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicy;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicyCatalog;
import io.github.flowerjvm.factory.application.certification.CertificationRepository;
import io.github.flowerjvm.factory.application.certification.CertificationStatus;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.policy.DefaultPolicyGate;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import java.util.Collection;
import java.util.Objects;

/** Authorizes one trusted exact Certification before duplicate reservation or result lookup. */
public final class CertificationIssuePolicyGate implements PolicyGate {
    private final CertificationRepository certifications;
    private final AgentPackCertificationPolicyCatalog trustedPolicy;
    private final PolicyGate baseline;

    public CertificationIssuePolicyGate(
            CertificationRepository certifications,
            AgentPackCertificationPolicy trustedPolicy) {
        this(certifications, trustedPolicy, new DefaultPolicyGate());
    }

    CertificationIssuePolicyGate(
            CertificationRepository certifications,
            AgentPackCertificationPolicy trustedPolicy,
            PolicyGate baseline) {
        this(certifications, AgentPackCertificationPolicyCatalog.singleton(trustedPolicy), baseline);
    }

    public CertificationIssuePolicyGate(
            CertificationRepository certifications,
            AgentPackCertificationPolicyCatalog trustedPolicy) {
        this(certifications, trustedPolicy, new DefaultPolicyGate());
    }

    CertificationIssuePolicyGate(
            CertificationRepository certifications,
            AgentPackCertificationPolicyCatalog trustedPolicy,
            PolicyGate baseline) {
        this.certifications = Objects.requireNonNull(certifications, "certifications");
        this.trustedPolicy = Objects.requireNonNull(trustedPolicy, "trustedPolicy");
        this.baseline = Objects.requireNonNull(baseline, "baseline");
    }

    @Override
    public PolicyDecision evaluate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        if (!CertificationIssueAction.ACTION_ID.equals(proposal.actionId())
                || !CertificationIssueAction.ACTION_ID.equals(definition.actionId())) {
            return PolicyDecision.deny("policy only accepts " + CertificationIssueAction.ACTION_ID);
        }
        PolicyDecision baselineDecision = baseline.evaluate(proposal, definition, context);
        if (!baselineDecision.allowedToExecuteNow()) {
            return baselineDecision;
        }
        if (context.tenantId() == null
                || context.tenantId().isBlank()
                || context.userId() == null
                || context.userId().isBlank()) {
            return PolicyDecision.deny("trusted tenant and execution principal are required");
        }
        if (!hasPermission(context.metadata().get("actor.permissions"), CertificationIssueAction.PERMISSION)) {
            return PolicyDecision.deny("execution principal lacks " + CertificationIssueAction.PERMISSION);
        }
        CertificationIssueInput input;
        try {
            input = CertificationIssueInput.from(proposal.input());
        } catch (IllegalArgumentException exception) {
            return PolicyDecision.deny("certification input is invalid");
        }
        if (!CertificationIssueAction.RESOURCE_TYPE.equals(context.metadata().get("resource.type"))
                || !input.certificationId().value().equals(context.metadata().get("resource.id"))) {
            return PolicyDecision.deny("trusted Certification resource scope does not match the request");
        }
        TenantId tenantId;
        try {
            tenantId = new TenantId(context.tenantId());
        } catch (IllegalArgumentException exception) {
            return PolicyDecision.deny("trusted tenant is invalid");
        }
        var certification = certifications.find(tenantId, input.certificationId()).orElse(null);
        if (certification == null
                || !certification.inputLock().tenantId().equals(tenantId)
                || !certification.inputLockArtifact().hash().equals(input.inputLockManifestHash())) {
            return PolicyDecision.deny("Certification is not visible through the trusted exact input lock");
        }
        if (!trustedPolicy.matches(certification.inputLock())) {
            return PolicyDecision.deny("Certification input lock is not admitted by current trusted policy");
        }
        if (input.expectedCertificationVersion() > certification.version()) {
            return PolicyDecision.deny("Certification version is from the future");
        }
        if (certification.status() == CertificationStatus.REQUESTED) {
            if (input.expectedCertificationVersion() != certification.version()) {
                return PolicyDecision.deny("Certification REQUESTED version is stale");
            }
        } else if (input.expectedCertificationVersion() != 0
                || input.expectedCertificationVersion() >= certification.version()) {
            return PolicyDecision.deny("only the original authorized issuance attempt may replay");
        }
        if (!CertificationIssueIdempotencyKeys
                .derive(certification, input.expectedCertificationVersion())
                .equals(proposal.idempotencyKey())) {
            return PolicyDecision.deny("idempotency key is not bound to the exact Certification version");
        }
        return PolicyDecision.allow();
    }

    private static boolean hasPermission(Object value, String required) {
        return value instanceof Collection<?> permissions && permissions.stream().anyMatch(required::equals);
    }
}
