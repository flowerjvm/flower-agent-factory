package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationEvidenceValidator;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationEvidenceManifest;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentManifest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Stages canonical evidence then manifest and wins the immutable Certification CAS at most once. */
public final class CertificationIssuanceService {
    private static final String EVIDENCE_REFERENCE_PREFIX = "factory-certification/evidence/";
    private static final String MANIFEST_REFERENCE_PREFIX = "factory-certification/component-manifest/";

    private final CertificationRepository certifications;
    private final CertificationIssuanceTransaction issuanceTransaction;
    private final ArtifactStore artifacts;
    private final CertificationArtifactCodec codec;
    private final Clock clock;
    private final VerificationRunRepository verificationRuns;
    private final VerificationEvidenceValidator fullEvidence;
    private final VerificationActionEvidenceOwner verificationOwner;

    public CertificationIssuanceService(
            CertificationRepository certifications,
            CertificationIssuanceTransaction issuanceTransaction,
            ArtifactStore artifacts,
            CertificationArtifactCodec codec,
            Clock clock) {
        this(certifications, issuanceTransaction, artifacts, codec, clock, null, null, null);
    }

    /** Production binding: full source integrity is checked off the Flower lane, not in its guard. */
    public CertificationIssuanceService(
            CertificationRepository certifications, CertificationIssuanceTransaction issuanceTransaction,
            ArtifactStore artifacts, CertificationArtifactCodec codec, Clock clock,
            VerificationRunRepository verificationRuns, VerificationEvidenceValidator fullEvidence,
            VerificationActionEvidenceOwner verificationOwner) {
        this.certifications = Objects.requireNonNull(certifications, "certifications");
        this.issuanceTransaction = Objects.requireNonNull(
                issuanceTransaction, "issuanceTransaction");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.clock = Objects.requireNonNull(clock, "clock");
        if ((verificationRuns == null) != (fullEvidence == null) || (verificationRuns == null) != (verificationOwner == null)) {
            throw new IllegalArgumentException("full certification evidence dependencies must be supplied together");
        }
        this.verificationRuns = verificationRuns;
        this.fullEvidence = fullEvidence;
        this.verificationOwner = verificationOwner;
    }

    /** Executes only the bounded, idempotent artifact-stage and domain-CAS sequence. */
    public CertificationIssuanceOutcome issue(CertificationDispatchIntent intent) {
        requireActiveIntent(intent);
        Certification current = find(intent);
        Optional<CertificationIssuanceOutcome> alreadyIssued = observeIssued(intent, current);
        if (alreadyIssued.isPresent()) {
            return alreadyIssued.orElseThrow();
        }
        requireRequestedIdentity(intent, current);

        ExpectedArtifacts expected = expectedArtifacts(current);
        stage(expected.evidenceArtifact());
        stage(expected.manifestArtifact());

        requireFullEvidence(intent);
        Instant certifiedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (intent.leaseUntil().filter(certifiedAt::isBefore).isEmpty()) {
            throw new EvidenceRejected();
        }
        Certification certified = current.certify(
                expected.manifestLock(),
                expected.evidenceLock(),
                intent.actionRunId(),
                certifiedAt,
                Optional.empty());
        CertificationIssuanceTransaction.CertificationIssuanceCommit commit =
                issuanceTransaction.commit(intent, current, certified);
        Certification canonical = commit.certification();
        CertificationIssuanceOutcome outcome = observeIssued(intent, canonical)
                .orElseThrow(() -> new IllegalStateException(
                        "Certification issuance transaction returned a non-canonical result"));
        return new CertificationIssuanceOutcome(
                outcome.certification(),
                outcome.certificationEvidence(),
                outcome.certificationManifest(),
                commit.committedNow());
    }

    /** Off-tick dispatch completion, including recovery of an already-committed issuance. */
    public void requireFullEvidence(CertificationDispatchIntent intent) {
        if (fullEvidence == null) return; // Source-compatible callers; production always supplies the full gate.
        try {
            Certification current = find(intent);
            if (!matchesImmutableIdentity(intent, current)) throw new EvidenceRejected();
            var lock = current.inputLock();
            var verification = verificationRuns.find(lock.tenantId(), lock.verificationRunId()).orElseThrow();
            if (!verification.tenantId().equals(lock.tenantId())
                    || !verification.buildSessionId().equals(lock.buildSessionId())
                    || !verification.candidateId().equals(lock.candidateId())
                    || !verification.candidateHash().equals(lock.candidateHash())
                    || !verification.verificationRunId().equals(lock.verificationRunId())
                    || !verification.gateProfile().equals(lock.gateProfile())
                    || !verification.toolchainLockHash().equals(lock.toolchainLock().hash())
                    || !verification.fixtureSetHash().equals(lock.verificationFixtureSetHash())
                    || verification.status() != VerificationRunStatus.PASSED
                    || verification.disposition().filter(VerificationDisposition.REVIEW_ELIGIBLE::equals).isEmpty()
                    || verification.resultManifestRef().filter(lock.verificationResultManifest().reference()::equals).isEmpty()
                    || verification.resultManifestHash().filter(lock.verificationResultManifest().hash()::equals).isEmpty()
                    || verificationOwner.assess(verification).status() != VerificationActionEvidenceOwner.Status.CANONICAL_SUCCEEDED
                    || !fullEvidence.isReviewEligible(verification)) throw new EvidenceRejected();
        } catch (RuntimeException invalid) {
            throw new EvidenceRejected();
        }
    }

    /** Known readback/lease rejection; a still-owned Action fails with manual review. */
    public static final class EvidenceRejected extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public EvidenceRejected() { super("CERTIFICATION_FULL_EVIDENCE_REJECTED"); }
    }

    /** Read-only crash recovery: an exact prior CAS is reused without staging or issuing again. */
    public Optional<CertificationIssuanceOutcome> observeIssued(CertificationDispatchIntent intent) {
        Objects.requireNonNull(intent, "intent");
        return certifications.find(intent.tenantId(), intent.certificationId())
                .flatMap(certification -> observeIssued(intent, certification));
    }

    private Optional<CertificationIssuanceOutcome> observeIssued(
            CertificationDispatchIntent intent, Certification certification) {
        if (!matchesImmutableIdentity(intent, certification)
                || certification.status() != CertificationStatus.CERTIFIED
                || certification.version() != intent.expectedCertificationVersion() + 1
                || certification.actionRunId().filter(intent.actionRunId()::equals).isEmpty()
                || certification.issuedAt().filter(certification.createdAt()::equals).isEmpty()
                || certification.expiresAt().isPresent()) {
            return Optional.empty();
        }
        ExpectedArtifacts expected = expectedArtifacts(certification);
        if (certification.certificationEvidence().filter(expected.evidenceLock()::equals).isEmpty()
                || certification.certificationManifest().filter(expected.manifestLock()::equals).isEmpty()
                || !storedExactly(expected.evidenceArtifact())
                || !storedExactly(expected.manifestArtifact())) {
            return Optional.empty();
        }
        return Optional.of(new CertificationIssuanceOutcome(
                certification, expected.evidenceLock(), expected.manifestLock(), false));
    }

    private Certification find(CertificationDispatchIntent intent) {
        return certifications.find(intent.tenantId(), intent.certificationId())
                .orElseThrow(() -> new IllegalStateException("Certification is not visible in trusted tenant"));
    }

    private static void requireActiveIntent(CertificationDispatchIntent intent) {
        Objects.requireNonNull(intent, "intent");
        if (intent.status() != CertificationDispatchIntentStatus.RUNNING
                || intent.claimToken().isEmpty()
                || intent.leaseUntil().isEmpty()) {
            throw new IllegalArgumentException("issuance requires one actively claimed intent");
        }
    }

    private static void requireRequestedIdentity(
            CertificationDispatchIntent intent, Certification certification) {
        if (!matchesImmutableIdentity(intent, certification)
                || certification.status() != CertificationStatus.REQUESTED
                || certification.version() != intent.expectedCertificationVersion()) {
            throw new IllegalStateException("Certification is not the exact REQUESTED issuance version");
        }
    }

    private static boolean matchesImmutableIdentity(
            CertificationDispatchIntent intent, Certification certification) {
        return certification != null
                && certification.certificationId().equals(intent.certificationId())
                && certification.inputLock().tenantId().equals(intent.tenantId())
                && certification.inputLockArtifact().hash().equals(intent.inputLockManifestHash());
    }

    private ExpectedArtifacts expectedArtifacts(Certification certification) {
        var lock = certification.inputLock();
        CertificationEvidenceManifest evidence = new CertificationEvidenceManifest(
                CertificationEvidenceManifest.SCHEMA_VERSION,
                lock.tenantId(),
                lock.productLineId(),
                lock.artifactType(),
                certification.certificationId(),
                lock.candidateId(),
                lock.candidateHash(),
                certification.inputLockArtifact().hash(),
                lock.verificationRunId(),
                lock.verificationActionRunId(),
                lock.verificationResultManifest().hash(),
                lock.compatibilityDescriptor().hash(),
                lock.certificationProfile(),
                lock.factoryVersion(),
                lock.flowerVersion(),
                lock.actionRuntimeVersion());
        Artifact evidenceArtifact = canonicalArtifact(
                lock.tenantId(), EVIDENCE_REFERENCE_PREFIX, codec.writeEvidence(evidence));
        CertificationArtifactLock evidenceLock = new CertificationArtifactLock(
                evidenceArtifact.reference(), evidenceArtifact.contentHash());

        CertifiedAgentComponentManifest manifest = new CertifiedAgentComponentManifest(
                CertifiedAgentComponentManifest.SCHEMA_VERSION,
                lock.tenantId(),
                lock.productLineId(),
                lock.artifactType(),
                certification.certificationId(),
                lock.candidateId(),
                lock.candidateHash(),
                lock.sourceManifest(),
                certification.inputLockArtifact(),
                lock.verificationRunId(),
                lock.verificationResultManifest(),
                lock.compatibilityDescriptor(),
                evidenceLock,
                lock.certificationProfile(),
                lock.factoryVersion(),
                certification.createdAt(),
                null,
                CertifiedAgentComponentManifest.CERTIFIED_STATUS);
        Artifact manifestArtifact = canonicalArtifact(
                lock.tenantId(), MANIFEST_REFERENCE_PREFIX, codec.writeComponentManifest(manifest));
        CertificationArtifactLock manifestLock = new CertificationArtifactLock(
                manifestArtifact.reference(), manifestArtifact.contentHash());
        return new ExpectedArtifacts(evidenceArtifact, evidenceLock, manifestArtifact, manifestLock);
    }

    private static Artifact canonicalArtifact(
            io.github.flowerjvm.factory.contracts.ids.TenantId tenantId,
            String referencePrefix,
            byte[] content) {
        ContentHash hash = sha256(content);
        return new Artifact(
                tenantId,
                new ArtifactReference(referencePrefix + hash.sha256()),
                hash,
                CertificationArtifactCodec.MEDIA_TYPE,
                content);
    }

    private void stage(Artifact expected) {
        Optional<Artifact> existing = artifacts.find(expected.tenantId(), expected.reference());
        if (existing.isPresent()) {
            requireExactArtifact(existing.orElseThrow(), expected);
            return;
        }
        ArtifactReference storedReference = artifacts.store(expected);
        if (!expected.reference().equals(storedReference)) {
            throw new IllegalStateException("artifact store returned a non-canonical reference");
        }
        Artifact stored = artifacts.find(expected.tenantId(), expected.reference())
                .orElseThrow(() -> new IllegalStateException("staged Certification artifact is not readable"));
        requireExactArtifact(stored, expected);
    }

    private boolean storedExactly(Artifact expected) {
        return artifacts.find(expected.tenantId(), expected.reference())
                .filter(actual -> exactArtifact(actual, expected))
                .isPresent();
    }

    private static void requireExactArtifact(Artifact actual, Artifact expected) {
        if (!exactArtifact(actual, expected)) {
            throw new IllegalStateException("Certification artifact reference contains conflicting content");
        }
    }

    private static boolean exactArtifact(Artifact actual, Artifact expected) {
        return actual.tenantId().equals(expected.tenantId())
                && actual.reference().equals(expected.reference())
                && actual.contentHash().equals(expected.contentHash())
                && actual.mediaType().equals(CertificationArtifactCodec.MEDIA_TYPE)
                && Arrays.equals(actual.content(), expected.content());
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private record ExpectedArtifacts(
            Artifact evidenceArtifact,
            CertificationArtifactLock evidenceLock,
            Artifact manifestArtifact,
            CertificationArtifactLock manifestLock) {}
}
