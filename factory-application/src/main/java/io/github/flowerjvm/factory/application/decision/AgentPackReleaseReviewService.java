package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.verification.AgentPackGenerationVerificationProfiles;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationEvidenceValidator;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;

/** Called only by a registered Factory Action; opens a request, never supplies a human decision. */
public final class AgentPackReleaseReviewService {
    public static final String OPTIONS_SCHEMA_ID = AgentPackReleaseReviewPolicy.OPTIONS_SCHEMA_ID;
    public static final String REQUIRED_PERMISSION = AgentPackReleaseReviewPolicy.REQUIRED_PERMISSION;
    private final BuildSessionRepository sessions;
    private final CandidateVersionRepository candidates;
    private final VerificationRunRepository verifications;
    private final AgentPackGenerationVerificationProfiles profiles;
    private final VerificationEvidenceValidator evidence;
    private final VerificationActionEvidenceOwner owners;
    private final ArtifactStore artifacts;
    private final AgentPackReleaseReviewTransaction transaction;
    private final Clock clock;
    private final AgentPackReleaseReviewPolicy policy;

    public AgentPackReleaseReviewService(
            BuildSessionRepository sessions, CandidateVersionRepository candidates,
            VerificationRunRepository verifications, AgentPackGenerationVerificationProfiles profiles,
            VerificationEvidenceValidator evidence, VerificationActionEvidenceOwner owners,
            ArtifactStore artifacts, AgentPackReleaseReviewTransaction transaction,
            Clock clock, Duration reviewWindow) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.verifications = Objects.requireNonNull(verifications, "verifications");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.owners = Objects.requireNonNull(owners, "owners");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policy = new AgentPackReleaseReviewPolicy(reviewWindow);
    }

    public AgentPackReleaseReviewResult ensureReleaseReview(TenantId trustedTenantId, BuildSessionId sessionId) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        Objects.requireNonNull(sessionId, "sessionId");
        BuildSession session = sessions.find(trustedTenantId, sessionId).orElseThrow(AgentPackReleaseReviewService::invalid);
        if (!session.tenantId().equals(trustedTenantId) || !session.buildSessionId().equals(sessionId)) {
            throw invalid();
        }
        CandidateVersion candidate = candidates.find(trustedTenantId,
                session.currentCandidateId().orElseThrow(AgentPackReleaseReviewService::invalid))
                .orElseThrow(AgentPackReleaseReviewService::invalid);
        String selectedProfile = profiles.profileFor(candidate);
        VerificationRun verification = verifications.findLatestForCandidate(trustedTenantId, sessionId,
                candidate.candidateId(), candidate.sourceHash(), selectedProfile)
                .orElseThrow(AgentPackReleaseReviewService::invalid);
        requireEvidence(session, candidate, verification, selectedProfile);
        Artifact source = exactArtifact(trustedTenantId, candidate.sourceManifestRef());
        byte[] question = policy.questionBytes(session, candidate, verification, source.contentHash());
        stage(trustedTenantId, policy.reference(), policy.bytes());
        ArtifactReference questionReference = policy.questionReference(question);
        stage(trustedTenantId, questionReference, question);
        // Re-evaluate after staging, before crossing the atomic ledger boundary. No worker claims
        // or caller-provided gate/profile/result are accepted as evidence.
        requireEvidence(session, candidate, verification, profiles.profileFor(candidate));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        DecisionPoint requested = policy.requestedPoint(session, candidate, questionReference, now);
        AgentPackReleaseReviewResult result = Objects.requireNonNull(
                transaction.ensureOpen(session, candidate, verification, requested), "review result");
        policy.requireExisting(session, candidate, requested, result.decisionPoint(), clock.instant());
        return result;
    }

    private void requireEvidence(BuildSession session, CandidateVersion candidate, VerificationRun verification,
            String selectedProfile) {
        AgentPackReleaseReviewPolicy.requireEligible(session, candidate, verification, clock.instant());
        if (!selectedProfile.equals(verification.gateProfile()) || !evidence.isReviewEligible(verification)
                || owners.assess(verification).status() != VerificationActionEvidenceOwner.Status.CANONICAL_SUCCEEDED) {
            throw invalid();
        }
    }

    private Artifact exactArtifact(TenantId tenant, ArtifactReference reference) {
        Artifact artifact = artifacts.find(tenant, reference).orElseThrow(AgentPackReleaseReviewService::invalid);
        if (!artifact.tenantId().equals(tenant) || !artifact.reference().equals(reference)
                || !AgentPackReleaseReviewPolicy.hash(artifact.content()).equals(artifact.contentHash())) {
            throw invalid();
        }
        return artifact;
    }

    private void stage(TenantId tenant, ArtifactReference reference, byte[] bytes) {
        ContentHash hash = AgentPackReleaseReviewPolicy.hash(bytes);
        if (!reference.equals(artifacts.store(new Artifact(tenant, reference, hash, "text/plain; charset=utf-8", bytes)))) {
            throw invalid();
        }
        Artifact stored = exactArtifact(tenant, reference);
        if (!stored.contentHash().equals(hash) || !Arrays.equals(bytes, stored.content())) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(AgentPackReleaseReviewPolicy.INELIGIBLE);
    }
}
