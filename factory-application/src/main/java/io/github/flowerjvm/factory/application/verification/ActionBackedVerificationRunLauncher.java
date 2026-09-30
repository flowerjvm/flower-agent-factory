package io.github.flowerjvm.factory.application.verification;

import io.github.flowerjvm.factory.application.action.VerificationRunAction;
import io.github.flowerjvm.factory.application.action.VerificationRunIdempotencyKeys;
import io.github.flowerjvm.factory.application.action.VerificationRunInput;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Creates one deterministic domain request and durably parks its governed verification Action once. */
public final class ActionBackedVerificationRunLauncher implements VerificationRunLauncher {
    public static final String GATE_PROFILE = "factory-v0.1-pr4";
    private static final String ID_VERSION = "factory.verification-run-id.v1";

    private final VerificationRunRequestTransaction requestTransaction;
    private final ActionRuntime actionRuntime;
    private final ContentHash fixtureSetHash;
    private final AgentPackGenerationVerificationProfiles profiles;

    public ActionBackedVerificationRunLauncher(
            VerificationRunRequestTransaction requestTransaction,
            ActionRuntime actionRuntime,
            ContentHash fixtureSetHash) {
        this(requestTransaction, actionRuntime, fixtureSetHash,
                AgentPackGenerationVerificationProfiles.legacyPr4Only());
    }

    public ActionBackedVerificationRunLauncher(
            VerificationRunRequestTransaction requestTransaction,
            ActionRuntime actionRuntime,
            ContentHash fixtureSetHash,
            AgentPackGenerationVerificationProfiles profiles) {
        this.requestTransaction = Objects.requireNonNull(requestTransaction, "requestTransaction");
        this.actionRuntime = Objects.requireNonNull(actionRuntime, "actionRuntime");
        this.fixtureSetHash = Objects.requireNonNull(fixtureSetHash, "fixtureSetHash");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Override
    public String profileFor(CandidateVersion candidate) {
        return profiles.profileFor(candidate);
    }

    @Override
    public VerificationRun ensureRequested(
            BuildSession buildSession,
            CandidateVersion candidate,
            Instant requestedAt) {
        Objects.requireNonNull(buildSession, "buildSession");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(requestedAt, "requestedAt");
        requireLaunchable(buildSession, candidate, requestedAt);
        String profile = profileFor(candidate);
        VerificationRun requested = new VerificationRun(
                deriveId(buildSession, candidate, fixtureSetHash, profile),
                buildSession.tenantId(),
                buildSession.buildSessionId(),
                candidate.candidateId(),
                candidate.sourceHash(),
                profile,
                candidate.toolchainLockHash(),
                fixtureSetHash,
                VerificationRunStatus.REQUESTED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                requestedAt,
                requestedAt);
        VerificationRun canonical = requestTransaction.request(requested);
        requireCanonicalRequest(requested, canonical);
        if (canonical.status() != VerificationRunStatus.REQUESTED) {
            return canonical;
        }
        var input = new VerificationRunInput(
                canonical.verificationRunId(), canonical.candidateId(), canonical.version());
        ActionProposal proposal = ActionProposal.builder(VerificationRunAction.ACTION_ID)
                .proposalId("verification-proposal-" + UUID.randomUUID())
                .requestChannel(ActionRequestChannel.INTERNAL)
                .proposerType(ActionProposerType.SERVICE)
                .requesterId("factory-verifier")
                .reason("Run the independent candidate gate " + profile)
                .input(input.toMap())
                .idempotencyKey(VerificationRunIdempotencyKeys.derive(canonical, candidate))
                .build();
        String actionRunId = UUID.randomUUID().toString();
        if (actionRunId.length() > 64) {
            throw new IllegalStateException("Action run id exceeds the persistence contract");
        }
        var context = new ExecutionContext(
                canonical.tenantId().value(),
                "factory-verifier",
                actionRunId,
                "verification:" + canonical.verificationRunId().value(),
                Map.of(
                        "actor.permissions", Set.of(VerificationRunAction.PERMISSION),
                        "resource.type", VerificationRunAction.RESOURCE_TYPE,
                        "resource.id", canonical.candidateId().value()));
        var actionResult = actionRuntime.handle(proposal, context);
        if (actionResult.status() != ActionExecutionStatus.ACCEPTED
                && actionResult.status() != ActionExecutionStatus.SUCCEEDED
                && !(actionResult.status() == ActionExecutionStatus.DENIED
                        && "DUPLICATE_ACTION".equals(actionResult.code()))) {
            // A concurrent durable owner may have advanced the domain after our transaction read.
            // Accept only progress proven by the same exact-identity transaction. A terminal
            // Action that failed before intent creation remains REQUESTED and is classified by
            // VerificationActionEvidenceOwner on the next Flow observation.
            VerificationRun refreshed = requestTransaction.request(requested);
            requireCanonicalRequest(requested, refreshed);
            if (refreshed.status() != VerificationRunStatus.REQUESTED) {
                return refreshed;
            }
            throw new IllegalStateException("verification Action rejected: " + actionResult.code());
        }
        return canonical;
    }

    public static VerificationRunId deriveId(
            BuildSession buildSession,
            CandidateVersion candidate,
            ContentHash fixtureSetHash) {
        return deriveId(buildSession, candidate, fixtureSetHash, GATE_PROFILE);
    }

    public static VerificationRunId deriveId(
            BuildSession buildSession,
            CandidateVersion candidate,
            ContentHash fixtureSetHash,
            String gateProfile) {
        Objects.requireNonNull(buildSession, "buildSession");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(fixtureSetHash, "fixtureSetHash");
        if (gateProfile == null || gateProfile.isBlank()
                || gateProfile.length() > 128 || gateProfile.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("gateProfile must be bounded non-control text");
        }
        String material = String.join(
                "\n",
                ID_VERSION,
                buildSession.tenantId().value(),
                buildSession.buildSessionId().value(),
                candidate.candidateId().value(),
                candidate.sourceHash().sha256(),
                candidate.dependencyLockRef().value(),
                candidate.dependencyLockHash().sha256(),
                gateProfile,
                candidate.toolchainLockRef().value(),
                candidate.toolchainLockHash().sha256(),
                fixtureSetHash.sha256());
        try {
            return new VerificationRunId("verify-" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static void requireLaunchable(
            BuildSession buildSession,
            CandidateVersion candidate,
            Instant requestedAt) {
        if (!buildSession.tenantId().equals(candidate.tenantId())
                || !buildSession.buildSessionId().equals(candidate.buildSessionId())
                || buildSession.status() != BuildSessionStatus.VERIFYING
                || buildSession.currentPhase() != BuildSessionPhase.TEST
                || buildSession.currentCandidateId().filter(candidate.candidateId()::equals).isEmpty()
                || buildSession.currentCandidateHash().filter(candidate.sourceHash()::equals).isEmpty()) {
            throw new IllegalArgumentException("BuildSession is not bound to this candidate in TEST verification");
        }
        if (buildSession.cancellationRequestedAt().isPresent() || !requestedAt.isBefore(buildSession.deadlineAt())) {
            throw new IllegalArgumentException("BuildSession is cancelled or past its verification deadline");
        }
    }

    private static void requireCanonicalRequest(VerificationRun requested, VerificationRun canonical) {
        Objects.requireNonNull(canonical, "canonical verification request");
        if (!requested.verificationRunId().equals(canonical.verificationRunId())
                || !requested.tenantId().equals(canonical.tenantId())
                || !requested.buildSessionId().equals(canonical.buildSessionId())
                || !requested.candidateId().equals(canonical.candidateId())
                || !requested.candidateHash().equals(canonical.candidateHash())
                || !requested.gateProfile().equals(canonical.gateProfile())
                || !requested.toolchainLockHash().equals(canonical.toolchainLockHash())
                || !requested.fixtureSetHash().equals(canonical.fixtureSetHash())) {
            throw new IllegalStateException("request transaction returned a different verification identity");
        }
    }
}
