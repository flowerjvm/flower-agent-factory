package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntent;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntentRepository;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntentStatus;
import io.github.flowerjvm.factory.application.verification.VerificationEvidenceValidator;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.AgentPackCompatibilityDescriptor;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Constructs one deterministic REQUESTED certification solely from trusted Agent Pack ledgers.
 *
 * <p>Canonical content-addressed artifacts are intentionally staged before the bounded database
 * transaction. A crash in that window is safe: reconstructing the same trusted input produces the
 * same bytes, hashes, references and Certification id.
 */
public final class AgentPackCertificationRequestService implements AgentPackCertificationRequester {
    public static final String SESSION_NOT_FOUND = "CERTIFICATION_REQUEST_SESSION_NOT_FOUND";
    public static final String SESSION_NOT_ELIGIBLE = "CERTIFICATION_REQUEST_SESSION_NOT_ELIGIBLE";
    public static final String CANDIDATE_NOT_FOUND = "CERTIFICATION_REQUEST_CANDIDATE_NOT_FOUND";
    public static final String CANDIDATE_MISMATCH = "CERTIFICATION_REQUEST_CANDIDATE_MISMATCH";
    public static final String WORK_ORDER_MISMATCH = "CERTIFICATION_REQUEST_WORK_ORDER_MISMATCH";
    public static final String GENERATION_INPUT_INVALID = "CERTIFICATION_REQUEST_GENERATION_INPUT_INVALID";
    public static final String GENERATION_INPUT_MISMATCH = "CERTIFICATION_REQUEST_GENERATION_INPUT_MISMATCH";
    public static final String VERIFICATION_NOT_ELIGIBLE =
            "CERTIFICATION_REQUEST_VERIFICATION_NOT_ELIGIBLE";
    public static final String VERIFICATION_EVIDENCE_INVALID =
            "CERTIFICATION_REQUEST_VERIFICATION_EVIDENCE_INVALID";
    public static final String VERIFICATION_OWNER_INVALID =
            "CERTIFICATION_REQUEST_VERIFICATION_OWNER_INVALID";
    public static final String POLICY_MISMATCH = "CERTIFICATION_REQUEST_POLICY_MISMATCH";
    public static final String ARTIFACT_INVALID = "CERTIFICATION_REQUEST_ARTIFACT_INVALID";
    public static final String TRANSACTION_INVALID = "CERTIFICATION_REQUEST_TRANSACTION_INVALID";

    private static final String COMPATIBILITY_REF_PREFIX =
            "factory-certification/agent-pack/compatibility/sha256/";
    private static final String INPUT_LOCK_REF_PREFIX =
            "factory-certification/agent-pack/input-lock/sha256/";

    private final BuildSessionRepository buildSessions;
    private final CandidateVersionRepository candidates;
    private final WorkOrderRepository workOrders;
    private final WorkerProtocolArtifacts workerArtifacts;
    private final VerificationRunRepository verifications;
    private final VerificationEvidenceValidator verificationEvidence;
    private final VerificationActionEvidenceOwner verificationOwner;
    private final VerificationDispatchIntentRepository verificationIntents;
    private final ArtifactStore artifacts;
    private final CertificationArtifactCodec codec;
    private final CertificationRequestTransaction transaction;
    private final AgentPackCertificationPolicyCatalog policies;
    private final Clock clock;

    public AgentPackCertificationRequestService(
            BuildSessionRepository buildSessions,
            CandidateVersionRepository candidates,
            WorkOrderRepository workOrders,
            WorkerProtocolArtifacts workerArtifacts,
            VerificationRunRepository verifications,
            VerificationEvidenceValidator verificationEvidence,
            VerificationActionEvidenceOwner verificationOwner,
            VerificationDispatchIntentRepository verificationIntents,
            ArtifactStore artifacts,
            CertificationArtifactCodec codec,
            CertificationRequestTransaction transaction,
            AgentPackCertificationPolicy policy,
            Clock clock) {
        this(buildSessions, candidates, workOrders, workerArtifacts, verifications, verificationEvidence,
                verificationOwner, verificationIntents, artifacts, codec, transaction,
                AgentPackCertificationPolicyCatalog.singleton(policy), clock);
    }

    public AgentPackCertificationRequestService(
            BuildSessionRepository buildSessions,
            CandidateVersionRepository candidates,
            WorkOrderRepository workOrders,
            WorkerProtocolArtifacts workerArtifacts,
            VerificationRunRepository verifications,
            VerificationEvidenceValidator verificationEvidence,
            VerificationActionEvidenceOwner verificationOwner,
            VerificationDispatchIntentRepository verificationIntents,
            ArtifactStore artifacts,
            CertificationArtifactCodec codec,
            CertificationRequestTransaction transaction,
            AgentPackCertificationPolicyCatalog policies,
            Clock clock) {
        this.buildSessions = Objects.requireNonNull(buildSessions, "buildSessions");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
        this.workerArtifacts = Objects.requireNonNull(workerArtifacts, "workerArtifacts");
        this.verifications = Objects.requireNonNull(verifications, "verifications");
        this.verificationEvidence = Objects.requireNonNull(verificationEvidence, "verificationEvidence");
        this.verificationOwner = Objects.requireNonNull(verificationOwner, "verificationOwner");
        this.verificationIntents = Objects.requireNonNull(verificationIntents, "verificationIntents");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Uses only the caller-authenticated tenant and durable BuildSession identity as authority. */
    @Override
    public AgentPackCertificationRequestOutcome ensureRequested(
            TenantId trustedTenantId, BuildSessionId buildSessionId) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Instant requestedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);

        BuildSession session = buildSessions.find(trustedTenantId, buildSessionId)
                .orElseThrow(() -> fail(SESSION_NOT_FOUND, "trusted BuildSession is missing"));
        BuildSession certifyingSession = requireAndTransitionSession(
                trustedTenantId, buildSessionId, session, requestedAt);
        CandidateVersion candidate = requireCandidate(trustedTenantId, session);
        WorkOrder generationOrder = requireGenerationOrder(trustedTenantId, session, candidate);
        CodingWorkerInputManifest generationInput = requireGenerationInput(generationOrder, candidate);

        CertificationArtifactLock sourceManifest = requireSourceManifest(
                trustedTenantId, session, candidate, generationInput);
        CertificationArtifactLock dependencyLock = exactLock(
                trustedTenantId,
                candidate.dependencyLockRef(),
                candidate.dependencyLockHash(),
                GENERATION_INPUT_INVALID,
                "candidate dependency lock is not exact");
        CertificationArtifactLock toolchainLock = exactLock(
                trustedTenantId,
                candidate.toolchainLockRef(),
                candidate.toolchainLockHash(),
                GENERATION_INPUT_INVALID,
                "candidate toolchain lock is not exact");
        CertificationArtifactLock generationInputLock = exactLock(
                trustedTenantId,
                generationOrder.inputArtifactManifestRef(),
                generationOrder.inputManifestHash(),
                GENERATION_INPUT_INVALID,
                "generation input manifest is not exact");
        CertificationArtifactLock productContract = exactLock(
                trustedTenantId,
                generationInput.productContractBundleRef(),
                generationInput.productContractBundleHash(),
                GENERATION_INPUT_INVALID,
                "product contract bundle is not exact");
        CertificationArtifactLock apiSignatureIndex = exactLock(
                trustedTenantId,
                generationInput.apiSignatureIndexRef(),
                generationInput.apiSignatureIndexHash(),
                GENERATION_INPUT_INVALID,
                "API signature index is not exact");
        CertificationArtifactLock policySnapshot = existingLock(
                trustedTenantId,
                generationOrder.policySnapshotRef(),
                GENERATION_INPUT_INVALID,
                "generation policy snapshot is not exact");

        AgentPackCertificationPolicy policy;
        try {
            policy = policies.policyForGeneration(generationInput);
            for (CertificationArtifactLock required : policies.requiredGenerationArtifacts(generationInput)) {
                workerArtifacts.exact(trustedTenantId, required.reference(), required.hash());
            }
        } catch (RuntimeException unadmittedGeneration) {
            throw fail(POLICY_MISMATCH, "generation inputs are not admitted by a trusted certification profile");
        }

        VerificationRun verification = requireVerification(
                trustedTenantId, session, candidate, generationInput, policy);
        CertificationArtifactLock verificationResult = exactLock(
                trustedTenantId,
                verification.resultManifestRef().orElseThrow(),
                verification.resultManifestHash().orElseThrow(),
                VERIFICATION_EVIDENCE_INVALID,
                "verification result manifest is not exact");
        VerificationDispatchIntent verificationIntent = requireVerificationOwner(
                trustedTenantId, candidate, verification);

        requirePolicyInputs(productContract, generationInput, verification, policy);
        AgentPackCompatibilityDescriptor compatibility = new AgentPackCompatibilityDescriptor(
                AgentPackCompatibilityDescriptor.SCHEMA_VERSION,
                trustedTenantId,
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                candidate.candidateId(),
                candidate.sourceHash(),
                productContract,
                apiSignatureIndex,
                dependencyLock,
                toolchainLock,
                policy.gateProfile(),
                policy.sourceLockAlgorithmId(),
                policy.factoryVersion(),
                policy.flowerVersion(),
                policy.actionRuntimeVersion());
        CertificationArtifactLock compatibilityLock = stageCanonical(
                trustedTenantId,
                COMPATIBILITY_REF_PREFIX,
                codec.writeCompatibilityDescriptor(compatibility));

        CertificationInputLock inputLock = new CertificationInputLock(
                CertificationInputLock.SCHEMA_VERSION,
                trustedTenantId,
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                session.buildSessionId(),
                generationOrder.workOrderId(),
                candidate.candidateId(),
                candidate.sourceHash(),
                sourceManifest,
                dependencyLock,
                toolchainLock,
                generationInputLock,
                productContract,
                apiSignatureIndex,
                policy.sourceLockAlgorithmId(),
                policy.gateProfile(),
                verification.verificationRunId(),
                verificationIntent.actionRunId(),
                verificationResult,
                policy.verificationFixtureSetHash(),
                policySnapshot,
                compatibilityLock,
                policy.certificationProfile(),
                policy.factoryVersion(),
                policy.flowerVersion(),
                policy.actionRuntimeVersion());
        if (!policies.matchesGeneration(inputLock, generationInput)) {
            throw fail(POLICY_MISMATCH, "constructed Certification input lock is not admitted by trusted policy");
        }
        CertificationArtifactLock inputLockArtifact = stageCanonical(
                trustedTenantId, INPUT_LOCK_REF_PREFIX, codec.writeInputLock(inputLock));
        Certification requested = Certification.requested(
                certificationId(trustedTenantId, inputLockArtifact.hash()),
                inputLock,
                inputLockArtifact,
                requestedAt);

        AgentPackCertificationRequestOutcome outcome;
        try {
            outcome = transaction.ensureRequested(session, certifyingSession, requested);
        } catch (AgentPackCertificationRequestException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw fail(TRANSACTION_INVALID, "certification request transaction failed closed");
        }
        return requireTransactionOutcome(outcome, requested, certifyingSession);
    }

    private static BuildSession requireAndTransitionSession(
            TenantId tenantId,
            BuildSessionId buildSessionId,
            BuildSession session,
            Instant requestedAt) {
        if (!session.tenantId().equals(tenantId)
                || !session.buildSessionId().equals(buildSessionId)
                || !ProductLineId.AGENT_PACK.equals(session.productLineId())
                || session.currentCandidateId().isEmpty()
                || session.currentCandidateHash().isEmpty()
                || session.currentCertificationId().isPresent()
                || session.cancellationRequestedAt().isPresent()
                || requestedAt.isBefore(session.updatedAt())
                || !requestedAt.isBefore(session.deadlineAt())) {
            throw fail(SESSION_NOT_ELIGIBLE, "BuildSession is not an exact live Agent Pack release authority");
        }
        if (session.status() == BuildSessionStatus.CANDIDATE_READY_FOR_RELEASE
                && session.currentPhase() == BuildSessionPhase.HUMAN_RELEASE_REVIEW) {
            try {
                return session.beginAgentPackCertification(requestedAt);
            } catch (RuntimeException invalid) {
                throw fail(SESSION_NOT_ELIGIBLE, "BuildSession cannot begin Agent Pack certification");
            }
        }
        if (session.status() == BuildSessionStatus.CERTIFYING
                && session.currentPhase() == BuildSessionPhase.CERTIFY) {
            return session;
        }
        throw fail(SESSION_NOT_ELIGIBLE, "BuildSession is not release-ready or an exact certification retry");
    }

    private CandidateVersion requireCandidate(TenantId tenantId, BuildSession session) {
        CandidateVersion candidate = candidates.find(tenantId, session.currentCandidateId().orElseThrow())
                .orElseThrow(() -> fail(CANDIDATE_NOT_FOUND, "current CandidateVersion is missing"));
        if (!candidate.tenantId().equals(tenantId)
                || !candidate.buildSessionId().equals(session.buildSessionId())
                || !candidate.candidateId().equals(session.currentCandidateId().orElseThrow())
                || !candidate.sourceHash().equals(session.currentCandidateHash().orElseThrow())
                || candidate.status() != CandidateVersionStatus.GENERATED) {
            throw fail(CANDIDATE_MISMATCH, "current CandidateVersion does not match the BuildSession authority");
        }
        return candidate;
    }

    private WorkOrder requireGenerationOrder(
            TenantId tenantId, BuildSession session, CandidateVersion candidate) {
        WorkOrder order = workOrders.find(tenantId, candidate.createdByWorkOrderId())
                .orElseThrow(() -> fail(WORK_ORDER_MISMATCH, "candidate generation WorkOrder is missing"));
        if (!order.tenantId().equals(tenantId)
                || !order.workOrderId().equals(candidate.createdByWorkOrderId())
                || !order.buildSessionId().equals(session.buildSessionId())
                || !BuildSessionPhase.GENERATE_CANDIDATE.id().equals(order.phase())) {
            throw fail(WORK_ORDER_MISMATCH, "candidate generation WorkOrder has a different trusted identity");
        }
        return order;
    }

    private CodingWorkerInputManifest requireGenerationInput(
            WorkOrder order, CandidateVersion candidate) {
        CodingWorkerInputManifest input;
        try {
            input = workerArtifacts.readInput(order);
        } catch (RuntimeException invalid) {
            throw fail(GENERATION_INPUT_INVALID, "generation input manifest failed its strict read gate");
        }
        if (!input.workOrderId().equals(order.workOrderId())
                || !input.buildSessionId().equals(order.buildSessionId())
                || !input.dependencyLockRef().equals(candidate.dependencyLockRef())
                || !input.dependencyLockHash().equals(candidate.dependencyLockHash())
                || !input.toolchainLockRef().equals(candidate.toolchainLockRef())
                || !input.toolchainLockHash().equals(candidate.toolchainLockHash())
                || input.repairLock().isPresent() != candidate.parentCandidateId().isPresent()
                || (input.repairLock().isPresent()
                        && (!input.repairLock().orElseThrow().baseCandidateId()
                                        .equals(candidate.parentCandidateId().orElseThrow())
                                || !order.candidateId()
                                        .filter(input.repairLock().orElseThrow().baseCandidateId()::equals)
                                        .isPresent()))) {
            throw fail(GENERATION_INPUT_MISMATCH, "generation input does not bind the current candidate");
        }
        return input;
    }

    private CertificationArtifactLock requireSourceManifest(
            TenantId tenantId,
            BuildSession session,
            CandidateVersion candidate,
            CodingWorkerInputManifest generationInput) {
        Artifact artifact = exactExistingArtifact(
                tenantId, candidate.sourceManifestRef(), ARTIFACT_INVALID, "candidate source manifest is missing");
        CandidateSourceManifest source;
        try {
            source = workerArtifacts.readCandidateSource(
                    tenantId, candidate.sourceManifestRef(), artifact.contentHash());
        } catch (RuntimeException invalid) {
            throw fail(ARTIFACT_INVALID, "candidate source manifest failed its strict read gate");
        }
        if (!source.candidateId().equals(candidate.candidateId())
                || !source.buildSessionId().equals(session.buildSessionId())
                || !source.candidateHash().equals(candidate.sourceHash())
                || !source.sourceLockAlgorithmId().equals(generationInput.sourceLockAlgorithmId())) {
            throw fail(CANDIDATE_MISMATCH, "candidate source manifest does not bind the current candidate");
        }
        return new CertificationArtifactLock(artifact.reference(), artifact.contentHash());
    }

    private VerificationRun requireVerification(
            TenantId tenantId,
            BuildSession session,
            CandidateVersion candidate,
            CodingWorkerInputManifest generationInput,
            AgentPackCertificationPolicy policy) {
        VerificationRun verification = verifications.findLatestForCandidate(
                        tenantId,
                        session.buildSessionId(),
                        candidate.candidateId(),
                        candidate.sourceHash(),
                        policy.gateProfile())
                .orElseThrow(() -> fail(VERIFICATION_NOT_ELIGIBLE, "latest exact verification is missing"));
        if (!verification.tenantId().equals(tenantId)
                || !verification.buildSessionId().equals(session.buildSessionId())
                || !verification.candidateId().equals(candidate.candidateId())
                || !verification.candidateHash().equals(candidate.sourceHash())
                || !verification.toolchainLockHash().equals(candidate.toolchainLockHash())
                || !verification.gateProfile().equals(generationInput.gateProfile())
                || !verification.gateProfile().equals(policy.gateProfile())
                || !verification.fixtureSetHash().equals(policy.verificationFixtureSetHash())
                || verification.status() != VerificationRunStatus.PASSED
                || verification.disposition().filter(VerificationDisposition.REVIEW_ELIGIBLE::equals).isEmpty()
                || verification.resultManifestRef().isEmpty()
                || verification.resultManifestHash().isEmpty()
                || VerificationRun.LEGACY_RESULT_MANIFEST_HASH.equals(
                        verification.resultManifestHash().orElseThrow())) {
            throw fail(VERIFICATION_NOT_ELIGIBLE, "latest verification is not exact and review eligible");
        }
        try {
            if (!verificationEvidence.isReviewEligible(verification)) {
                throw fail(VERIFICATION_NOT_ELIGIBLE, "verification evidence is not review eligible");
            }
        } catch (AgentPackCertificationRequestException failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw fail(VERIFICATION_EVIDENCE_INVALID, "verification evidence read gate failed closed");
        }
        return verification;
    }

    private VerificationDispatchIntent requireVerificationOwner(
            TenantId tenantId, CandidateVersion candidate, VerificationRun verification) {
        VerificationActionEvidenceOwner.Assessment owner;
        VerificationDispatchIntent intent;
        try {
            owner = verificationOwner.assess(verification);
            intent = verificationIntents.findLatest(tenantId, verification.verificationRunId())
                    .orElseThrow(() -> fail(VERIFICATION_OWNER_INVALID, "verification dispatch intent is missing"));
        } catch (AgentPackCertificationRequestException failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw fail(VERIFICATION_OWNER_INVALID, "verification Action owner read failed closed");
        }
        long proposalVersion = verification.version() - 2;
        if (owner.status() != VerificationActionEvidenceOwner.Status.CANONICAL_SUCCEEDED
                || intent.status() != VerificationDispatchIntentStatus.COMPLETED
                || !intent.tenantId().equals(tenantId)
                || !intent.verificationRunId().equals(verification.verificationRunId())
                || !intent.candidateId().equals(candidate.candidateId())
                || intent.expectedVerificationRunVersion() != proposalVersion) {
            throw fail(VERIFICATION_OWNER_INVALID, "verification is not bound to its exact canonical Action owner");
        }
        return intent;
    }

    private void requirePolicyInputs(
            CertificationArtifactLock productContract,
            CodingWorkerInputManifest generationInput,
            VerificationRun verification,
            AgentPackCertificationPolicy policy) {
        if (!policy.productContractBundle().equals(productContract)
                || !policy.gateProfile().equals(generationInput.gateProfile())
                || !policy.gateProfile().equals(verification.gateProfile())
                || !policy.verificationFixtureSetHash().equals(verification.fixtureSetHash())
                || !policy.sourceLockAlgorithmId().equals(generationInput.sourceLockAlgorithmId())) {
            throw fail(POLICY_MISMATCH, "trusted Agent Pack certification policy does not admit the inputs");
        }
    }

    private CertificationArtifactLock exactLock(
            TenantId tenantId,
            ArtifactReference reference,
            ContentHash expectedHash,
            String code,
            String message) {
        try {
            Artifact exact = workerArtifacts.exact(tenantId, reference, expectedHash);
            return new CertificationArtifactLock(exact.reference(), exact.contentHash());
        } catch (RuntimeException invalid) {
            throw fail(code, message);
        }
    }

    private CertificationArtifactLock existingLock(
            TenantId tenantId, ArtifactReference reference, String code, String message) {
        Artifact exact = exactExistingArtifact(tenantId, reference, code, message);
        return new CertificationArtifactLock(exact.reference(), exact.contentHash());
    }

    private Artifact exactExistingArtifact(
            TenantId tenantId, ArtifactReference reference, String code, String message) {
        Artifact artifact;
        try {
            artifact = artifacts.find(tenantId, reference).orElse(null);
        } catch (RuntimeException invalid) {
            throw fail(code, message);
        }
        if (artifact == null
                || !artifact.tenantId().equals(tenantId)
                || !artifact.reference().equals(reference)
                || !artifact.contentHash().equals(sha256(artifact.content()))) {
            throw fail(code, message);
        }
        return artifact;
    }

    private CertificationArtifactLock stageCanonical(
            TenantId tenantId, String referencePrefix, byte[] canonicalBytes) {
        Objects.requireNonNull(canonicalBytes, "canonicalBytes");
        ContentHash hash = sha256(canonicalBytes);
        ArtifactReference reference = new ArtifactReference(referencePrefix + hash.sha256());
        Artifact staged = new Artifact(
                tenantId, reference, hash, CertificationArtifactCodec.MEDIA_TYPE, canonicalBytes);
        try {
            ArtifactReference storedReference = artifacts.store(staged);
            if (!reference.equals(storedReference)) {
                throw fail(ARTIFACT_INVALID, "artifact store returned a different canonical reference");
            }
        } catch (AgentPackCertificationRequestException failure) {
            throw failure;
        } catch (RuntimeException possibleDuplicate) {
            // A content-addressed retry may race the first insert. The exact immutable observation
            // below is the only condition under which that failure can be treated as convergence.
        }
        Artifact observed = exactExistingArtifact(
                tenantId, reference, ARTIFACT_INVALID, "canonical certification artifact was not stored exactly");
        if (!CertificationArtifactCodec.MEDIA_TYPE.equals(observed.mediaType())
                || !observed.contentHash().equals(hash)
                || !Arrays.equals(observed.content(), canonicalBytes)) {
            throw fail(ARTIFACT_INVALID, "canonical certification artifact collided with different content");
        }
        return new CertificationArtifactLock(reference, hash);
    }

    private static CertificationId certificationId(TenantId tenantId, ContentHash inputLockHash) {
        String material = "factory.agent-pack-certification.v1\n"
                + tenantId.value()
                + "\n"
                + inputLockHash.sha256();
        return new CertificationId("cert-agent-pack-" + sha256(material.getBytes(StandardCharsets.UTF_8)).sha256());
    }

    private static AgentPackCertificationRequestOutcome requireTransactionOutcome(
            AgentPackCertificationRequestOutcome outcome,
            Certification requested,
            BuildSession certifyingSession) {
        if (outcome == null) {
            throw fail(TRANSACTION_INVALID, "certification request transaction returned no outcome");
        }
        Certification canonical = outcome.certification();
        BuildSession canonicalSession = outcome.buildSession();
        if (outcome.disposition() == CertificationRequestDisposition.CONFLICT) {
            return outcome;
        }
        if (!canonical.certificationId().equals(requested.certificationId())
                || !canonical.inputLock().equals(requested.inputLock())
                || !canonical.inputLockArtifact().equals(requested.inputLockArtifact())
                || canonical.status() != CertificationStatus.REQUESTED
                || !canonicalSession.tenantId().equals(certifyingSession.tenantId())
                || !canonicalSession.buildSessionId().equals(certifyingSession.buildSessionId())
                || canonicalSession.status() != BuildSessionStatus.CERTIFYING
                || canonicalSession.currentPhase() != BuildSessionPhase.CERTIFY
                || !canonicalSession.currentCandidateId().equals(certifyingSession.currentCandidateId())
                || !canonicalSession.currentCandidateHash().equals(certifyingSession.currentCandidateHash())) {
            throw fail(TRANSACTION_INVALID, "transaction returned a non-canonical certification request pair");
        }
        if (outcome.disposition() == CertificationRequestDisposition.CREATED
                && !canonicalSession.equals(certifyingSession)) {
            throw fail(TRANSACTION_INVALID, "created request did not persist the exact BuildSession transition");
        }
        return outcome;
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static AgentPackCertificationRequestException fail(String code, String message) {
        return new AgentPackCertificationRequestException(code, message);
    }
}
