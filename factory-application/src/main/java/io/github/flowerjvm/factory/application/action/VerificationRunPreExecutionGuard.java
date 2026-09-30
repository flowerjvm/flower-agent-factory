package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.application.verification.AgentPackGenerationVerificationProfiles;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionDecision;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionGuard;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import java.time.Clock;
import java.util.Objects;

/** Rechecks all mutable and immutable candidate locks immediately before async submission. */
public final class VerificationRunPreExecutionGuard implements PreExecutionGuard {
    private final BuildSessionRepository buildSessions;
    private final CandidateVersionRepository candidates;
    private final VerificationRunRepository verificationRuns;
    private final Clock clock;
    private final ContentHash fixtureSetHash;
    private final AgentPackGenerationVerificationProfiles profiles;

    public VerificationRunPreExecutionGuard(
            BuildSessionRepository buildSessions,
            CandidateVersionRepository candidates,
            VerificationRunRepository verificationRuns,
            ContentHash fixtureSetHash,
            Clock clock) {
        this(buildSessions, candidates, verificationRuns, fixtureSetHash, clock,
                AgentPackGenerationVerificationProfiles.legacyPr4Only());
    }

    public VerificationRunPreExecutionGuard(
            BuildSessionRepository buildSessions,
            CandidateVersionRepository candidates,
            VerificationRunRepository verificationRuns,
            ContentHash fixtureSetHash,
            Clock clock,
            AgentPackGenerationVerificationProfiles profiles) {
        this.buildSessions = Objects.requireNonNull(buildSessions, "buildSessions");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.verificationRuns = Objects.requireNonNull(verificationRuns, "verificationRuns");
        this.fixtureSetHash = Objects.requireNonNull(fixtureSetHash, "fixtureSetHash");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Override
    public PreExecutionDecision check(
            ActionProposal proposal,
            ActionDefinition definition,
            ExecutionContext context,
            PolicyDecision policyDecision) {
        if (!VerificationRunAction.ACTION_ID.equals(proposal.actionId())
                || !VerificationRunAction.ACTION_ID.equals(definition.actionId())) {
            return deny("VERIFICATION_ACTION_MISMATCH", "guard only accepts verification Actions");
        }
        VerificationRunInput input;
        TenantId tenantId;
        try {
            input = VerificationRunInput.from(proposal.input());
            tenantId = new TenantId(context.tenantId());
        } catch (IllegalArgumentException exception) {
            return deny("VERIFICATION_SCOPE_INVALID", exception.getMessage());
        }
        var run = verificationRuns.find(tenantId, input.verificationRunId()).orElse(null);
        var candidate = candidates.find(tenantId, input.candidateId()).orElse(null);
        if (run == null || candidate == null) {
            return deny("VERIFICATION_RESOURCE_NOT_FOUND", "verification resource is not visible");
        }
        if (run.status() != VerificationRunStatus.REQUESTED
                || run.version() != input.expectedVerificationRunVersion()) {
            return deny("VERIFICATION_RUN_STALE", "VerificationRun is no longer the requested version");
        }
        if (!run.candidateId().equals(candidate.candidateId())
                || !run.buildSessionId().equals(candidate.buildSessionId())
                || !run.candidateHash().equals(candidate.sourceHash())
                || !run.toolchainLockHash().equals(candidate.toolchainLockHash())
                || !fixtureSetHash.equals(run.fixtureSetHash())) {
            return deny("VERIFICATION_CANDIDATE_LOCK_MISMATCH", "candidate locks do not match");
        }
        try {
            profiles.requireMatches(candidate, run);
        } catch (RuntimeException invalidProfile) {
            return deny("VERIFICATION_GENERATION_PROFILE_INVALID", "generation profile locks do not match");
        }
        var session = buildSessions.find(tenantId, run.buildSessionId()).orElse(null);
        if (session == null
                || session.status() != BuildSessionStatus.VERIFYING
                || session.currentPhase() != BuildSessionPhase.TEST
                || session.currentCandidateId().filter(run.candidateId()::equals).isEmpty()
                || session.currentCandidateHash().filter(run.candidateHash()::equals).isEmpty()) {
            return deny("BUILD_SESSION_NOT_VERIFIABLE", "BuildSession is not bound to this candidate in TEST");
        }
        if (session.cancellationRequestedAt().isPresent() || !clock.instant().isBefore(session.deadlineAt())) {
            return deny("BUILD_SESSION_NOT_VERIFIABLE", "BuildSession is cancelled or past its deadline");
        }
        return PreExecutionDecision.allow();
    }

    private static PreExecutionDecision deny(String code, String reason) {
        return PreExecutionDecision.deny(code, reason);
    }
}
