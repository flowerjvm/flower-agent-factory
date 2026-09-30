package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntentRepository;
import io.github.flowerjvm.factory.application.verification.VerificationEvidenceValidator;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.AgentPackCompatibilityDescriptor;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationEvidenceManifest;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentManifest;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Function;

/**
 * Strict same-tenant read gate for consuming a certified AGENT_PACK in another product line.
 * Product-contract admission and profile policy remain issuer-policy concerns; this gate proves
 * stored identity, evidence and compatibility self-consistency.
 */
public final class CertifiedComponentResolver implements CertifiedAgentComponentReadGate {
    public static final String CERTIFICATION_NOT_FOUND = "CERTIFIED_COMPONENT_CERTIFICATION_NOT_FOUND";
    public static final String CERTIFICATION_NOT_ACTIVE = "CERTIFIED_COMPONENT_CERTIFICATION_NOT_ACTIVE";
    public static final String CERTIFICATION_EXPIRED = "CERTIFIED_COMPONENT_CERTIFICATION_EXPIRED";
    public static final String CERTIFICATION_UNBOUND = "CERTIFIED_COMPONENT_CERTIFICATION_UNBOUND";
    public static final String LOCK_MISMATCH = "CERTIFIED_COMPONENT_LOCK_MISMATCH";
    public static final String CANDIDATE_MISMATCH = "CERTIFIED_COMPONENT_CANDIDATE_MISMATCH";
    public static final String VERIFICATION_NOT_ELIGIBLE = "CERTIFIED_COMPONENT_VERIFICATION_NOT_ELIGIBLE";
    public static final String VERIFICATION_LEGACY_EVIDENCE = "CERTIFIED_COMPONENT_VERIFICATION_LEGACY_EVIDENCE";
    public static final String VERIFICATION_UNBOUND = "CERTIFIED_COMPONENT_VERIFICATION_UNBOUND";
    public static final String ARTIFACT_INVALID = "CERTIFIED_COMPONENT_ARTIFACT_INVALID";

    private final CertificationRepository certifications;
    private final CertificationActionEvidenceOwner certificationOwner;
    private final BuildSessionRepository buildSessions;
    private final WorkOrderRepository workOrders;
    private final CandidateVersionRepository candidates;
    private final VerificationRunRepository verifications;
    private final VerificationDispatchIntentRepository verificationIntents;
    private final ArtifactStore artifacts;
    private final WorkerProtocolArtifacts workerProtocolArtifacts;
    private final VerificationEvidenceValidator verificationEvidence;
    private final VerificationActionEvidenceOwner verificationOwner;
    private final CertificationArtifactCodec codec;
    private final Clock clock;

    public CertifiedComponentResolver(
            CertificationRepository certifications,
            CertificationActionEvidenceOwner certificationOwner,
            BuildSessionRepository buildSessions,
            WorkOrderRepository workOrders,
            CandidateVersionRepository candidates,
            VerificationRunRepository verifications,
            VerificationDispatchIntentRepository verificationIntents,
            ArtifactStore artifacts,
            WorkerProtocolArtifacts workerProtocolArtifacts,
            VerificationEvidenceValidator verificationEvidence,
            VerificationActionEvidenceOwner verificationOwner,
            CertificationArtifactCodec codec,
            Clock clock) {
        this.certifications = Objects.requireNonNull(certifications, "certifications");
        this.certificationOwner = Objects.requireNonNull(certificationOwner, "certificationOwner");
        this.buildSessions = Objects.requireNonNull(buildSessions, "buildSessions");
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.verifications = Objects.requireNonNull(verifications, "verifications");
        this.verificationIntents = Objects.requireNonNull(verificationIntents, "verificationIntents");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.workerProtocolArtifacts = Objects.requireNonNull(workerProtocolArtifacts, "workerProtocolArtifacts");
        this.verificationEvidence = Objects.requireNonNull(verificationEvidence, "verificationEvidence");
        this.verificationOwner = Objects.requireNonNull(verificationOwner, "verificationOwner");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Resolves every authority-bearing payload from tenant-scoped immutable storage. */
    @Override
    public ResolvedCertifiedAgentComponent resolve(
            TenantId trustedTenantId, CertifiedAgentComponentRef reference) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        Objects.requireNonNull(reference, "reference");
        requireAgentPack(reference.productLineId(), reference.artifactType());

        Certification certification = certifications.find(trustedTenantId, reference.certificationId())
                .orElseThrow(() -> fail(CERTIFICATION_NOT_FOUND, "certification was not found in the trusted tenant"));
        if (!certification.certificationId().equals(reference.certificationId())
                || !certification.inputLock().tenantId().equals(trustedTenantId)) {
            throw fail(LOCK_MISMATCH, "certification repository returned a different trusted identity");
        }
        if (certification.status() != CertificationStatus.CERTIFIED) {
            throw fail(CERTIFICATION_NOT_ACTIVE, "certification is not currently CERTIFIED");
        }
        if (certification.expiresAt().filter(expiry -> !clock.instant().isBefore(expiry)).isPresent()) {
            throw fail(CERTIFICATION_EXPIRED, "certification has expired");
        }
        CertificationActionEvidenceOwner.Assessment certificationActionOwner;
        try {
            certificationActionOwner = certificationOwner.assess(certification);
        } catch (RuntimeException invalidOwner) {
            throw fail(CERTIFICATION_UNBOUND, "certification Action owner could not be trusted");
        }
        if (certificationActionOwner.status()
                != CertificationActionEvidenceOwner.Status.CANONICAL_SUCCEEDED) {
            throw fail(CERTIFICATION_UNBOUND, "certification is not bound to a canonical issuance Action");
        }
        CertificationArtifactLock manifestLock = certification.certificationManifest().orElseThrow(
                () -> fail(CERTIFICATION_NOT_ACTIVE, "certification manifest is missing"));
        CertificationArtifactLock evidenceLock = certification.certificationEvidence().orElseThrow(
                () -> fail(CERTIFICATION_NOT_ACTIVE, "certification evidence is missing"));
        if (!certification.inputLockArtifact().equals(reference.inputLockManifest())
                || !manifestLock.equals(reference.certificationManifest())
                || !evidenceLock.equals(reference.certificationEvidence())) {
            throw fail(LOCK_MISMATCH, "component reference does not match the certification ledger");
        }

        CertificationInputLock inputLock = readCanonical(
                trustedTenantId, certification.inputLockArtifact(), codec::readInputLock);
        CertificationEvidenceManifest evidence = readCanonical(
                trustedTenantId, evidenceLock, codec::readEvidence);
        CertifiedAgentComponentManifest manifest = readCanonical(
                trustedTenantId, manifestLock, codec::readComponentManifest);
        AgentPackCompatibilityDescriptor compatibility = readCanonical(
                trustedTenantId, inputLock.compatibilityDescriptor(), codec::readCompatibilityDescriptor);
        if (!certification.inputLock().equals(inputLock)) {
            throw fail(LOCK_MISMATCH, "stored input lock differs from the certification ledger");
        }
        requireArtifactBindings(trustedTenantId, reference, inputLock, evidence, manifest, compatibility, certification);

        var session = buildSessions.find(trustedTenantId, inputLock.buildSessionId())
                .orElseThrow(() -> fail(CANDIDATE_MISMATCH, "producer BuildSession is missing"));
        if (!session.tenantId().equals(trustedTenantId)
                || !session.buildSessionId().equals(inputLock.buildSessionId())
                || !ProductLineId.AGENT_PACK.equals(session.productLineId())) {
            throw fail(CANDIDATE_MISMATCH, "producer BuildSession is not the agent-pack line");
        }
        WorkOrder workOrder = workOrders.find(trustedTenantId, inputLock.generationWorkOrderId())
                .orElseThrow(() -> fail(CANDIDATE_MISMATCH, "generation WorkOrder is missing"));
        if (!workOrder.tenantId().equals(trustedTenantId)
                || !workOrder.workOrderId().equals(inputLock.generationWorkOrderId())
                || !workOrder.buildSessionId().equals(inputLock.buildSessionId())
                || !workOrder.inputArtifactManifestRef().equals(inputLock.generationInputManifest().reference())
                || !workOrder.inputManifestHash().equals(inputLock.generationInputManifest().hash())
                || !workOrder.policySnapshotRef().equals(inputLock.policySnapshot().reference())) {
            throw fail(CANDIDATE_MISMATCH, "generation WorkOrder does not match the certification input lock");
        }
        CodingWorkerInputManifest generationInput;
        try {
            generationInput = workerProtocolArtifacts.readInput(workOrder);
        } catch (RuntimeException invalid) {
            throw fail(ARTIFACT_INVALID, "generation input manifest failed its strict ref/hash read gate");
        }
        requireGenerationInputBindings(inputLock, generationInput);

        var candidate = candidates.find(trustedTenantId, inputLock.candidateId())
                .orElseThrow(() -> fail(CANDIDATE_MISMATCH, "candidate is missing"));
        if (!candidate.tenantId().equals(trustedTenantId)
                || !candidate.candidateId().equals(inputLock.candidateId())
                || !candidate.buildSessionId().equals(inputLock.buildSessionId())
                || !candidate.createdByWorkOrderId().equals(inputLock.generationWorkOrderId())
                || !candidate.sourceManifestRef().equals(inputLock.sourceManifest().reference())
                || !candidate.sourceHash().equals(inputLock.candidateHash())
                || !candidate.dependencyLockRef().equals(inputLock.dependencyLock().reference())
                || !candidate.dependencyLockHash().equals(inputLock.dependencyLock().hash())
                || !candidate.toolchainLockRef().equals(inputLock.toolchainLock().reference())
                || !candidate.toolchainLockHash().equals(inputLock.toolchainLock().hash())) {
            throw fail(CANDIDATE_MISMATCH, "candidate does not match the exact certification lock");
        }

        VerificationRun verification = verifications.find(trustedTenantId, inputLock.verificationRunId())
                .orElseThrow(() -> fail(VERIFICATION_NOT_ELIGIBLE, "verification run is missing"));
        if (!verification.tenantId().equals(trustedTenantId)
                || !verification.verificationRunId().equals(inputLock.verificationRunId())) {
            throw fail(VERIFICATION_NOT_ELIGIBLE, "verification repository returned a different trusted identity");
        }
        requireEligibleVerification(inputLock, verification);
        if (!verificationEvidence.isReviewEligible(verification)) {
            throw fail(VERIFICATION_NOT_ELIGIBLE, "verification result manifest failed its evidence read gate");
        }
        VerificationActionEvidenceOwner.Assessment owner = verificationOwner.assess(verification);
        var intent = verificationIntents.findLatest(trustedTenantId, verification.verificationRunId());
        if (owner.status() != VerificationActionEvidenceOwner.Status.CANONICAL_SUCCEEDED
                || intent.isEmpty()
                || !intent.orElseThrow().tenantId().equals(trustedTenantId)
                || !intent.orElseThrow().verificationRunId().equals(verification.verificationRunId())
                || !intent.orElseThrow().candidateId().equals(verification.candidateId())
                || !intent.orElseThrow().actionRunId().equals(inputLock.verificationActionRunId())) {
            throw fail(VERIFICATION_UNBOUND, "verification is not bound to the exact canonical Action owner");
        }

        requireOpaqueArtifacts(trustedTenantId, inputLock);
        return new ResolvedCertifiedAgentComponent(
                reference, certification, inputLock, evidence, manifest, compatibility, candidate, verification);
    }

    private static void requireArtifactBindings(
            TenantId tenantId,
            CertifiedAgentComponentRef reference,
            CertificationInputLock lock,
            CertificationEvidenceManifest evidence,
            CertifiedAgentComponentManifest manifest,
            AgentPackCompatibilityDescriptor compatibility,
            Certification certification) {
        if (!tenantId.equals(lock.tenantId())
                || !tenantId.equals(evidence.tenantId())
                || !tenantId.equals(manifest.tenantId())
                || !tenantId.equals(compatibility.tenantId())) {
            throw fail(LOCK_MISMATCH, "component artifacts do not belong to the trusted tenant");
        }
        if (!reference.productLineId().equals(lock.productLineId())
                || reference.artifactType() != lock.artifactType()
                || !reference.candidateId().equals(lock.candidateId())
                || !reference.candidateHash().equals(lock.candidateHash())
                || !reference.sourceManifest().equals(lock.sourceManifest())
                || !reference.verificationRunId().equals(lock.verificationRunId())
                || !reference.verificationResultManifest().equals(lock.verificationResultManifest())
                || !reference.compatibilityDescriptor().equals(lock.compatibilityDescriptor())
                || !reference.certificationProfile().equals(lock.certificationProfile())) {
            throw fail(LOCK_MISMATCH, "downstream component reference does not match the input lock");
        }
        if (!evidence.productLineId().equals(lock.productLineId())
                || evidence.artifactType() != lock.artifactType()
                || !evidence.certificationId().equals(certification.certificationId())
                || !evidence.candidateId().equals(lock.candidateId())
                || !evidence.candidateHash().equals(lock.candidateHash())
                || !evidence.inputLockManifestHash().equals(certification.inputLockArtifact().hash())
                || !evidence.verificationRunId().equals(lock.verificationRunId())
                || !evidence.verificationActionRunId().equals(lock.verificationActionRunId())
                || !evidence.verificationResultManifestHash().equals(lock.verificationResultManifest().hash())
                || !evidence.compatibilityDescriptorHash().equals(lock.compatibilityDescriptor().hash())
                || !evidence.certificationProfile().equals(lock.certificationProfile())
                || !evidence.factoryVersion().equals(lock.factoryVersion())
                || !evidence.flowerVersion().equals(lock.flowerVersion())
                || !evidence.actionRuntimeVersion().equals(lock.actionRuntimeVersion())) {
            throw fail(LOCK_MISMATCH, "certification evidence does not match the exact input lock");
        }
        if (!manifest.productLineId().equals(lock.productLineId())
                || manifest.artifactType() != lock.artifactType()
                || !manifest.certificationId().equals(certification.certificationId())
                || !manifest.candidateId().equals(lock.candidateId())
                || !manifest.candidateHash().equals(lock.candidateHash())
                || !manifest.sourceManifest().equals(lock.sourceManifest())
                || !manifest.inputLockManifest().equals(certification.inputLockArtifact())
                || !manifest.verificationRunId().equals(lock.verificationRunId())
                || !manifest.verificationResultManifest().equals(lock.verificationResultManifest())
                || !manifest.compatibilityDescriptor().equals(lock.compatibilityDescriptor())
                || !manifest.certificationEvidence().equals(certification.certificationEvidence().orElseThrow())
                || !manifest.certificationProfile().equals(lock.certificationProfile())
                || !manifest.factoryVersion().equals(lock.factoryVersion())
                || !manifest.issuedAt().equals(certification.issuedAt().orElseThrow())
                || !java.util.Optional.ofNullable(manifest.expiresAt()).equals(certification.expiresAt())) {
            throw fail(LOCK_MISMATCH, "certified component manifest does not match the certification ledger");
        }
        if (!compatibility.productLineId().equals(lock.productLineId())
                || compatibility.artifactType() != lock.artifactType()
                || !compatibility.candidateId().equals(lock.candidateId())
                || !compatibility.candidateHash().equals(lock.candidateHash())
                || !compatibility.productContractBundle().equals(lock.productContractBundle())
                || !compatibility.apiSignatureIndex().equals(lock.apiSignatureIndex())
                || !compatibility.dependencyLock().equals(lock.dependencyLock())
                || !compatibility.toolchainLock().equals(lock.toolchainLock())
                || !compatibility.gateProfile().equals(lock.gateProfile())
                || !compatibility.sourceLockAlgorithmId().equals(lock.sourceLockAlgorithmId())
                || !compatibility.factoryVersion().equals(lock.factoryVersion())
                || !compatibility.flowerVersion().equals(lock.flowerVersion())
                || !compatibility.actionRuntimeVersion().equals(lock.actionRuntimeVersion())) {
            throw fail(LOCK_MISMATCH, "compatibility descriptor does not match the exact input lock");
        }
    }

    private static void requireGenerationInputBindings(
            CertificationInputLock lock, CodingWorkerInputManifest input) {
        if (!input.workOrderId().equals(lock.generationWorkOrderId())
                || !input.buildSessionId().equals(lock.buildSessionId())
                || !input.dependencyLockRef().equals(lock.dependencyLock().reference())
                || !input.dependencyLockHash().equals(lock.dependencyLock().hash())
                || !input.toolchainLockRef().equals(lock.toolchainLock().reference())
                || !input.toolchainLockHash().equals(lock.toolchainLock().hash())
                || !input.apiSignatureIndexRef().equals(lock.apiSignatureIndex().reference())
                || !input.apiSignatureIndexHash().equals(lock.apiSignatureIndex().hash())
                || !input.productContractBundleRef().equals(lock.productContractBundle().reference())
                || !input.productContractBundleHash().equals(lock.productContractBundle().hash())
                || !input.gateProfile().equals(lock.gateProfile())
                || !input.sourceLockAlgorithmId().equals(lock.sourceLockAlgorithmId())) {
            throw fail(LOCK_MISMATCH, "generation input manifest does not match the certification lock");
        }
    }

    private static void requireEligibleVerification(CertificationInputLock lock, VerificationRun verification) {
        if (!verification.buildSessionId().equals(lock.buildSessionId())
                || !verification.candidateId().equals(lock.candidateId())
                || !verification.candidateHash().equals(lock.candidateHash())
                || !verification.gateProfile().equals(lock.gateProfile())
                || !verification.toolchainLockHash().equals(lock.toolchainLock().hash())
                || !verification.fixtureSetHash().equals(lock.verificationFixtureSetHash())
                || !verification.resultManifestRef().equals(java.util.Optional.of(
                        lock.verificationResultManifest().reference()))
                || !verification.resultManifestHash().equals(java.util.Optional.of(
                        lock.verificationResultManifest().hash()))) {
            throw fail(VERIFICATION_NOT_ELIGIBLE, "verification run does not match the certification lock");
        }
        if (VerificationRun.LEGACY_RESULT_MANIFEST_HASH.equals(lock.verificationResultManifest().hash())) {
            throw fail(VERIFICATION_LEGACY_EVIDENCE, "legacy verification evidence cannot authorize certification");
        }
        if (verification.status() != VerificationRunStatus.PASSED
                || verification.disposition().filter(value -> value == VerificationDisposition.REVIEW_ELIGIBLE)
                        .isEmpty()) {
            throw fail(VERIFICATION_NOT_ELIGIBLE, "verification is not PASSED and review eligible");
        }
    }

    private void requireOpaqueArtifacts(TenantId tenantId, CertificationInputLock lock) {
        requireArtifact(tenantId, lock.sourceManifest());
        requireArtifact(tenantId, lock.dependencyLock());
        requireArtifact(tenantId, lock.toolchainLock());
        requireArtifact(tenantId, lock.generationInputManifest());
        requireArtifact(tenantId, lock.productContractBundle());
        requireArtifact(tenantId, lock.apiSignatureIndex());
        requireArtifact(tenantId, lock.verificationResultManifest());
        requireArtifact(tenantId, lock.policySnapshot());
    }

    private <T> T readCanonical(
            TenantId tenantId, CertificationArtifactLock lock, Function<byte[], T> reader) {
        Artifact artifact = requireArtifact(tenantId, lock);
        if (!CertificationArtifactCodec.MEDIA_TYPE.equals(artifact.mediaType())) {
            throw fail(ARTIFACT_INVALID, "certification artifact media type is not application/json");
        }
        try {
            return reader.apply(artifact.content());
        } catch (RuntimeException invalid) {
            throw fail(ARTIFACT_INVALID, "certification artifact is not strict canonical JSON");
        }
    }

    private Artifact requireArtifact(TenantId tenantId, CertificationArtifactLock lock) {
        Artifact artifact;
        try {
            artifact = artifacts.find(tenantId, lock.reference()).orElse(null);
        } catch (RuntimeException corrupt) {
            throw fail(ARTIFACT_INVALID, "certification artifact lookup failed integrity checks");
        }
        if (artifact == null
                || !artifact.tenantId().equals(tenantId)
                || !artifact.reference().equals(lock.reference())
                || !artifact.contentHash().equals(lock.hash())
                || !sha256(artifact.content()).equals(lock.hash())) {
            throw fail(ARTIFACT_INVALID, "certification artifact is missing or has a different hash");
        }
        return artifact;
    }

    private static void requireAgentPack(ProductLineId productLineId, CertifiedArtifactType artifactType) {
        if (!ProductLineId.AGENT_PACK.equals(productLineId) || artifactType != CertifiedArtifactType.AGENT_PACK) {
            throw fail(LOCK_MISMATCH, "only exact AGENT_PACK components are supported");
        }
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static CertifiedComponentResolutionException fail(String code, String message) {
        return new CertifiedComponentResolutionException(code, message);
    }
}
