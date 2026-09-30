package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/** Stages one deterministic shipment manifest and delegates the final authority check to SQL. */
public final class ReferenceAssemblyReleaseService {
    private final ReferenceAssemblyRepository assemblies;
    private final ReferenceAssemblyReleaseTransaction releaseTransaction;
    private final ReferenceAssemblyArtifactSupport artifactSupport;
    private final Clock clock;
    private final CertifiedAgentComponentReadGate fullComponents;

    public ReferenceAssemblyReleaseService(
            ReferenceAssemblyRepository assemblies,
            ReferenceAssemblyReleaseTransaction releaseTransaction,
            ArtifactStore artifacts,
            ReferenceAssemblyArtifactCodec codec,
            Clock clock) {
        this(assemblies, releaseTransaction, artifacts, codec, clock, null);
    }

    /** Production release uses the full off-tick component gate, never a tick receipt projection. */
    public ReferenceAssemblyReleaseService(
            ReferenceAssemblyRepository assemblies, ReferenceAssemblyReleaseTransaction releaseTransaction,
            ArtifactStore artifacts, ReferenceAssemblyArtifactCodec codec, Clock clock,
            CertifiedAgentComponentReadGate fullComponents) {
        this.assemblies = Objects.requireNonNull(assemblies, "assemblies");
        this.releaseTransaction = Objects.requireNonNull(
                releaseTransaction, "releaseTransaction");
        this.artifactSupport = new ReferenceAssemblyArtifactSupport(artifacts, codec);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.fullComponents = fullComponents;
    }

    /** Executes only bounded canonical staging plus the final transactional release. */
    public ReferenceAssemblyReleaseOutcome release(
            ReferenceAssemblyReleaseDispatchIntent intent) {
        requireActiveIntent(intent);
        ReferenceAssembly current = find(intent);
        Optional<ReferenceAssemblyReleaseOutcome> prior = observeReleased(intent, current);
        if (prior.isPresent()) {
            return prior.orElseThrow();
        }
        requireExactActionBound(intent, current);

        ReferenceAssemblyReleaseManifest manifest = expectedManifest(intent, current);
        CertificationArtifactLock manifestLock =
                artifactSupport.stageReleaseManifest(intent.tenantId(), manifest);
        requireFullEvidence(intent);
        Instant releasedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (intent.leaseUntil().filter(releasedAt::isBefore).isEmpty()) {
            throw new EvidenceRejected();
        }
        ReferenceAssembly proposed = current.release(
                manifestLock,
                intent.releaseDecisionPointId(),
                intent.releaseSubjectHash(),
                intent.actionRunId(),
                releasedAt);
        ReferenceAssemblyReleaseTransaction.ReleaseCommit commit =
                releaseTransaction.commit(intent, current, proposed);
        ReferenceAssemblyReleaseOutcome canonical = observeReleased(
                        intent, commit.referenceAssembly())
                .orElseThrow(() -> new IllegalStateException(
                        "release transaction returned a non-canonical Reference Assembly"));
        return new ReferenceAssemblyReleaseOutcome(
                canonical.referenceAssembly(),
                canonical.releaseManifest(),
                commit.committedNow());
    }

    /** Off-tick only: called before commit and before Action success, including crash recovery. */
    public void requireFullEvidence(ReferenceAssemblyReleaseDispatchIntent intent) {
        if (fullComponents == null) return; // Source-compatible callers; production always supplies the full gate.
        try {
            ReferenceAssembly current = find(intent);
            if (!matchesImmutableIdentity(intent, current)) throw new EvidenceRejected();
            var manifest = expectedManifest(intent, current);
            var resolved = Objects.requireNonNull(fullComponents.resolve(intent.tenantId(), manifest.component()));
            if (!resolved.reference().equals(manifest.component())
                    || !resolved.inputLock().tenantId().equals(intent.tenantId())) throw new EvidenceRejected();
        } catch (RuntimeException invalid) {
            throw new EvidenceRejected();
        }
    }

    /** Known readback/lease rejection; a still-owned Action fails with manual review. */
    public static final class EvidenceRejected extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public EvidenceRejected() { super("REFERENCE_ASSEMBLY_FULL_EVIDENCE_REJECTED"); }
    }

    /** Read-only crash recovery for an exact prior release commit. */
    public Optional<ReferenceAssemblyReleaseOutcome> observeReleased(
            ReferenceAssemblyReleaseDispatchIntent intent) {
        Objects.requireNonNull(intent, "intent");
        return assemblies.find(intent.tenantId(), intent.referenceAssemblyId())
                .flatMap(assembly -> observeReleased(intent, assembly));
    }

    private Optional<ReferenceAssemblyReleaseOutcome> observeReleased(
            ReferenceAssemblyReleaseDispatchIntent intent, ReferenceAssembly assembly) {
        if (!matchesImmutableIdentity(intent, assembly)
                || assembly.status() != ReferenceAssemblyStatus.RELEASED
                || assembly.version() != intent.expectedReferenceAssemblyVersion() + 2
                || assembly.releaseActionRunId().filter(intent.actionRunId()::equals).isEmpty()
                || assembly.releaseManifest().isEmpty()) {
            return Optional.empty();
        }
        CertificationArtifactLock lock = assembly.releaseManifest().orElseThrow();
        ReferenceAssemblyReleaseManifest expected;
        ReferenceAssemblyReleaseManifest stored;
        try {
            expected = expectedManifest(intent, assembly);
            stored = artifactSupport.readReleaseManifest(intent.tenantId(), lock);
        } catch (RuntimeException invalidArtifact) {
            return Optional.empty();
        }
        return expected.equals(stored)
                ? Optional.of(new ReferenceAssemblyReleaseOutcome(assembly, lock, false))
                : Optional.empty();
    }

    private ReferenceAssemblyReleaseManifest expectedManifest(
            ReferenceAssemblyReleaseDispatchIntent intent, ReferenceAssembly assembly) {
        ReferenceAssemblyRequirement requirement =
                artifactSupport.readRequirement(intent.tenantId(), assembly.requirement());
        if (!requirement.consumerContract().equals(assembly.consumerContract())
                || !requirement.hostFixture().equals(assembly.hostFixture())
                || !requirement.policySnapshot().equals(assembly.policySnapshot())
                || !requirement.component().certificationId()
                        .equals(assembly.componentCertificationId())
                || !requirement.component().candidateHash()
                        .equals(assembly.componentCandidateHash())
                || !requirement.component().certificationManifest()
                        .equals(assembly.componentCertificationManifest())) {
            throw new IllegalStateException(
                    "release requirement graph differs from the durable assembly locks");
        }
        return new ReferenceAssemblyReleaseManifest(
                ReferenceAssemblyReleaseManifest.SCHEMA_VERSION,
                ProductLineId.REFERENCE_ASSEMBLY,
                ReferenceAssemblyReleaseManifest.RELEASE_ALGORITHM_ID,
                assembly.referenceAssemblyId(),
                assembly.requirement(),
                assembly.consumerContract(),
                assembly.hostFixture(),
                assembly.policySnapshot(),
                requirement.component(),
                assembly.assemblyManifest().orElseThrow(),
                assembly.inspectionReport().orElseThrow(),
                intent.releaseDecisionPointId(),
                intent.releaseSubjectHash(),
                intent.actionRunId());
    }

    private ReferenceAssembly find(ReferenceAssemblyReleaseDispatchIntent intent) {
        return assemblies.find(intent.tenantId(), intent.referenceAssemblyId())
                .orElseThrow(() -> new IllegalStateException(
                        "Reference Assembly is not visible in trusted tenant"));
    }

    private static void requireActiveIntent(
            ReferenceAssemblyReleaseDispatchIntent intent) {
        Objects.requireNonNull(intent, "intent");
        if (intent.status() != ReferenceAssemblyReleaseDispatchIntentStatus.RUNNING
                || intent.claimToken().isEmpty()
                || intent.leaseUntil().isEmpty()) {
            throw new IllegalArgumentException(
                    "release requires one actively claimed durable intent");
        }
    }

    private static void requireExactActionBound(
            ReferenceAssemblyReleaseDispatchIntent intent, ReferenceAssembly assembly) {
        if (!matchesImmutableIdentity(intent, assembly)
                || assembly.status() != ReferenceAssemblyStatus.INSPECTED
                || assembly.version() != intent.expectedReferenceAssemblyVersion() + 1
                || assembly.releaseManifest().isPresent()
                || assembly.releaseActionRunId().filter(intent.actionRunId()::equals).isEmpty()) {
            throw new IllegalStateException(
                    "Reference Assembly is not the exact Action-bound release version");
        }
    }

    static boolean matchesImmutableIdentity(
            ReferenceAssemblyReleaseDispatchIntent intent, ReferenceAssembly assembly) {
        return assembly != null
                && assembly.tenantId().equals(intent.tenantId())
                && assembly.referenceAssemblyId().equals(intent.referenceAssemblyId())
                && assembly.assemblyManifest()
                        .map(lock -> lock.hash().equals(intent.assemblyManifestHash()))
                        .orElse(false)
                && assembly.inspectionReport()
                        .map(lock -> lock.hash().equals(intent.inspectionReportHash()))
                        .orElse(false)
                && assembly.releaseDecisionPointId()
                        .filter(intent.releaseDecisionPointId()::equals)
                        .isPresent()
                && assembly.releaseSubjectHash()
                        .filter(intent.releaseSubjectHash()::equals)
                        .isPresent();
    }
}
