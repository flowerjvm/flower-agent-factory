package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.verification.AgentPackGenerationVerificationProfiles;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.policy.DefaultPolicyGate;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import java.util.Collection;
import java.util.Objects;

/** Authorizes current principal/tenant and repository-verified candidate visibility before duplicates. */
public final class VerificationRunPolicyGate implements PolicyGate {
    private final VerificationRunRepository verificationRuns;
    private final CandidateVersionRepository candidates;
    private final PolicyGate baseline;
    private final AgentPackGenerationVerificationProfiles profiles;

    public VerificationRunPolicyGate(
            VerificationRunRepository verificationRuns,
            CandidateVersionRepository candidates) {
        this(verificationRuns, candidates, new DefaultPolicyGate());
    }

    VerificationRunPolicyGate(
            VerificationRunRepository verificationRuns,
            CandidateVersionRepository candidates,
            PolicyGate baseline) {
        this(verificationRuns, candidates, baseline, AgentPackGenerationVerificationProfiles.legacyPr4Only());
    }

    public VerificationRunPolicyGate(
            VerificationRunRepository verificationRuns,
            CandidateVersionRepository candidates,
            AgentPackGenerationVerificationProfiles profiles) {
        this(verificationRuns, candidates, new DefaultPolicyGate(), profiles);
    }

    private VerificationRunPolicyGate(
            VerificationRunRepository verificationRuns,
            CandidateVersionRepository candidates,
            PolicyGate baseline,
            AgentPackGenerationVerificationProfiles profiles) {
        this.verificationRuns = Objects.requireNonNull(verificationRuns, "verificationRuns");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.baseline = Objects.requireNonNull(baseline, "baseline");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Override
    public PolicyDecision evaluate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context) {
        if (!VerificationRunAction.ACTION_ID.equals(proposal.actionId())
                || !VerificationRunAction.ACTION_ID.equals(definition.actionId())) {
            return PolicyDecision.deny("policy only accepts " + VerificationRunAction.ACTION_ID);
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
        if (!hasPermission(context.metadata().get("actor.permissions"), VerificationRunAction.PERMISSION)) {
            return PolicyDecision.deny("execution principal lacks " + VerificationRunAction.PERMISSION);
        }
        VerificationRunInput input;
        try {
            input = VerificationRunInput.from(proposal.input());
        } catch (IllegalArgumentException exception) {
            return PolicyDecision.deny("verification input is invalid");
        }
        if (!VerificationRunAction.RESOURCE_TYPE.equals(context.metadata().get("resource.type"))
                || !input.candidateId().value().equals(context.metadata().get("resource.id"))) {
            return PolicyDecision.deny("trusted candidate resource scope does not match the request");
        }
        TenantId tenantId;
        try {
            tenantId = new TenantId(context.tenantId());
        } catch (IllegalArgumentException exception) {
            return PolicyDecision.deny("trusted tenant is invalid");
        }
        var candidate = candidates.find(tenantId, input.candidateId()).orElse(null);
        var run = verificationRuns.find(tenantId, input.verificationRunId()).orElse(null);
        if (candidate == null
                || run == null
                || !run.candidateId().equals(candidate.candidateId())
                || !run.buildSessionId().equals(candidate.buildSessionId())
                || !run.candidateHash().equals(candidate.sourceHash())
                || !run.toolchainLockHash().equals(candidate.toolchainLockHash())) {
            return PolicyDecision.deny("verification resource is not visible in trusted candidate scope");
        }
        try {
            profiles.requireMatches(candidate, run);
        } catch (RuntimeException invalidProfile) {
            return PolicyDecision.deny("verification generation profile is not authorized");
        }
        // An authorized transport replay of an older logical attempt must reach the durable
        // duplicate policy so it can observe that attempt's unchanged first result. Only the
        // exact current REQUESTED version can execute because the pre-execution guard enforces it.
        if (input.expectedVerificationRunVersion() > run.version()) {
            return PolicyDecision.deny("VerificationRun version is from the future");
        }
        if (!VerificationRunIdempotencyKeys.derive(run, candidate, input.expectedVerificationRunVersion())
                .equals(proposal.idempotencyKey())) {
            return PolicyDecision.deny("idempotency key is not bound to the VerificationRun version");
        }
        return PolicyDecision.allow();
    }

    private static boolean hasPermission(Object value, String required) {
        return value instanceof Collection<?> permissions && permissions.stream().anyMatch(required::equals);
    }
}
