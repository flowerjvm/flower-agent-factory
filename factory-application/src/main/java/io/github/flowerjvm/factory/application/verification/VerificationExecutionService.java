package io.github.flowerjvm.factory.application.verification;

import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.verification.VerificationRequest;
import io.github.flowerjvm.factory.contracts.verification.VerificationResult;
import io.github.flowerjvm.factory.contracts.verification.VerificationStatus;
import io.github.flowerjvm.factory.contracts.verification.Verifier;
import java.time.Clock;
import java.util.Objects;

/**
 * Runs one evidence-bound verifier call outside a Flower tick and commits first terminal truth by CAS.
 * Only this invocation may perform REQUESTED -> RUNNING; observing RUNNING is effect-uncertain and
 * never licenses a second verifier invocation.
 */
public final class VerificationExecutionService {
    private final VerificationRunRepository verificationRuns;
    private final CandidateVersionRepository candidates;
    private final ArtifactStore artifacts;
    private final Verifier verifier;
    private final Clock clock;
    private final io.github.flowerjvm.factory.contracts.artifact.ArtifactReference fixtureSetRef;
    private final AgentPackGenerationVerificationProfiles profiles;

    public VerificationExecutionService(
            VerificationRunRepository verificationRuns,
            CandidateVersionRepository candidates,
            ArtifactStore artifacts,
            Verifier verifier,
            Clock clock,
            io.github.flowerjvm.factory.contracts.artifact.ArtifactReference fixtureSetRef) {
        this(verificationRuns, candidates, artifacts, verifier, clock, fixtureSetRef,
                AgentPackGenerationVerificationProfiles.legacyPr4Only());
    }

    public VerificationExecutionService(
            VerificationRunRepository verificationRuns,
            CandidateVersionRepository candidates,
            ArtifactStore artifacts,
            Verifier verifier,
            Clock clock,
            io.github.flowerjvm.factory.contracts.artifact.ArtifactReference fixtureSetRef,
            AgentPackGenerationVerificationProfiles profiles) {
        this.verificationRuns = Objects.requireNonNull(verificationRuns, "verificationRuns");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.fixtureSetRef = Objects.requireNonNull(fixtureSetRef, "fixtureSetRef");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    public VerificationExecutionOutcome execute(
            TenantId tenantId,
            VerificationRunId verificationRunId,
            long expectedVersion) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(verificationRunId, "verificationRunId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        VerificationRun observed = requireRun(tenantId, verificationRunId);
        if (observed.status().isTerminal()) {
            return new VerificationExecutionOutcome(observed, false);
        }
        if (observed.version() != expectedVersion) {
            throw new IllegalStateException("VerificationRun version changed before execution");
        }
        var candidate = candidates.find(tenantId, observed.candidateId())
                .orElseThrow(() -> new IllegalStateException("CandidateVersion is not visible in tenant scope"));
        requireImmutableBinding(observed, candidate);
        profiles.requireMatches(candidate, observed);
        VerificationRun running = observed;
        if (observed.status() == VerificationRunStatus.REQUESTED) {
            VerificationRun started = observed.start(clock.instant());
            if (!verificationRuns.compareAndSet(observed, started)) {
                VerificationRun canonical = requireRun(tenantId, verificationRunId);
                if (canonical.status().isTerminal()) {
                    return new VerificationExecutionOutcome(canonical, false);
                }
                throw new IllegalStateException("VerificationRun CAS lost before verifier execution");
            }
            running = started;
        } else {
            throw new IllegalStateException(
                    "VerificationRun is already RUNNING; verifier ownership is uncertain");
        }

        var manifestArtifact = artifacts.find(tenantId, candidate.sourceManifestRef())
                .orElseThrow(() -> new IllegalStateException("candidate source manifest artifact is missing"));
        if (!manifestArtifact.tenantId().equals(tenantId)
                || !manifestArtifact.reference().equals(candidate.sourceManifestRef())) {
            throw new IllegalStateException("candidate source manifest artifact escaped its tenant/reference lock");
        }
        // sourceHash locks the source set, not the manifest JSON bytes. The Verifier strictly parses
        // this immutable artifact and recomputes that source lock before executing commands.
        Objects.requireNonNull(manifestArtifact.contentHash(), "candidate manifest artifact hash");
        requireArtifactLock(tenantId, candidate.dependencyLockRef(), candidate.dependencyLockHash(), "dependency");
        // The host-owned verifier installs the canonical static toolchain and fixture set
        // idempotently for this tenant inside this registered Action before reading them. No
        // candidate bytes select either trusted policy input.

        VerificationResult result = verifier.verify(new VerificationRequest(
                tenantId,
                running.verificationRunId(),
                running.buildSessionId(),
                running.candidateId(),
                candidate.sourceManifestRef(),
                running.candidateHash(),
                candidate.dependencyLockRef(),
                candidate.dependencyLockHash(),
                candidate.toolchainLockRef(),
                running.gateProfile(),
                running.toolchainLockHash(),
                fixtureSetRef,
                running.fixtureSetHash()));
        var resultArtifact = artifacts.find(tenantId, result.resultManifestRef())
                .orElseThrow(() -> new IllegalStateException("verification result manifest artifact is missing"));
        if (!resultArtifact.tenantId().equals(tenantId)
                || !resultArtifact.reference().equals(result.resultManifestRef())
                || !resultArtifact.contentHash().equals(result.resultManifestHash())) {
            throw new IllegalStateException("verification result manifest hash does not match stored artifact");
        }
        VerificationRun terminal = running.complete(
                result.status() == VerificationStatus.PASSED
                        ? VerificationRunStatus.PASSED
                        : VerificationRunStatus.FAILED,
                result.resultManifestRef(),
                result.resultManifestHash(),
                result.stableCodes().getFirst(),
                result.disposition(),
                clock.instant());
        if (!verificationRuns.compareAndSet(running, terminal)) {
            VerificationRun canonical = requireRun(tenantId, verificationRunId);
            if (!canonical.status().isTerminal()) {
                throw new IllegalStateException("VerificationRun terminal CAS lost without terminal winner");
            }
            return new VerificationExecutionOutcome(canonical, false);
        }
        VerificationRun canonical = requireRun(tenantId, verificationRunId);
        if (!canonical.status().isTerminal()) {
            throw new IllegalStateException("VerificationRun terminal CAS did not produce canonical terminal truth");
        }
        return new VerificationExecutionOutcome(canonical, true);
    }

    private VerificationRun requireRun(TenantId tenantId, VerificationRunId id) {
        return verificationRuns.find(tenantId, id)
                .orElseThrow(() -> new IllegalStateException("VerificationRun is not visible in tenant scope"));
    }

    private void requireArtifactLock(
            TenantId tenantId,
            io.github.flowerjvm.factory.contracts.artifact.ArtifactReference reference,
            ContentHash hash,
            String kind) {
        var artifact = artifacts.find(tenantId, reference)
                .orElseThrow(() -> new IllegalStateException(kind + " lock artifact is missing"));
        if (!artifact.tenantId().equals(tenantId)
                || !artifact.reference().equals(reference)
                || !artifact.contentHash().equals(hash)) {
            throw new IllegalStateException(kind + " lock artifact escaped its exact ref/hash binding");
        }
    }

    private static void requireImmutableBinding(
            VerificationRun run,
            io.github.flowerjvm.factory.application.candidate.CandidateVersion candidate) {
        if (!candidate.tenantId().equals(run.tenantId())
                || !candidate.buildSessionId().equals(run.buildSessionId())
                || !candidate.candidateId().equals(run.candidateId())
                || !candidate.sourceHash().equals(run.candidateHash())
                || !candidate.toolchainLockHash().equals(run.toolchainLockHash())) {
            throw new IllegalStateException("VerificationRun is not bound to the immutable CandidateVersion");
        }
    }
}
