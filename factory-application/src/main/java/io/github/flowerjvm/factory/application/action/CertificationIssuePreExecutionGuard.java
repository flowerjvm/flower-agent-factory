package io.github.flowerjvm.factory.application.action;

import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicy;
import io.github.flowerjvm.factory.application.certification.AgentPackCertificationPolicyCatalog;
import io.github.flowerjvm.factory.application.certification.CertificationRepository;
import io.github.flowerjvm.factory.application.certification.CertificationStatus;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntent;
import io.github.flowerjvm.factory.application.verification.VerificationDispatchIntentRepository;
import io.github.flowerjvm.factory.application.verification.VerificationEvidenceValidator;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunRepository;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.action.ActionDefinition;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionDecision;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionGuard;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyDecision;
import java.time.Clock;
import java.util.Objects;

/** Rechecks the complete trusted Agent Pack evidence chain immediately before issuance dispatch. */
public final class CertificationIssuePreExecutionGuard implements PreExecutionGuard {
    private final CertificationRepository certifications;
    private final BuildSessionRepository buildSessions;
    private final WorkOrderRepository workOrders;
    private final WorkerProtocolArtifacts workerProtocolArtifacts;
    private final CandidateVersionRepository candidates;
    private final VerificationRunRepository verificationRuns;
    private final VerificationEvidenceValidator verificationEvidence;
    private final VerificationActionEvidenceOwner verificationOwner;
    private final VerificationDispatchIntentRepository verificationIntents;
    private final AgentPackCertificationPolicyCatalog trustedPolicy;
    private final Clock clock;

    public CertificationIssuePreExecutionGuard(
            CertificationRepository certifications,
            BuildSessionRepository buildSessions,
            WorkOrderRepository workOrders,
            WorkerProtocolArtifacts workerProtocolArtifacts,
            CandidateVersionRepository candidates,
            VerificationRunRepository verificationRuns,
            VerificationEvidenceValidator verificationEvidence,
            VerificationActionEvidenceOwner verificationOwner,
            VerificationDispatchIntentRepository verificationIntents,
            AgentPackCertificationPolicy trustedPolicy,
            Clock clock) {
        this(certifications, buildSessions, workOrders, workerProtocolArtifacts, candidates,
                verificationRuns, verificationEvidence, verificationOwner, verificationIntents,
                AgentPackCertificationPolicyCatalog.singleton(trustedPolicy), clock);
    }

    public CertificationIssuePreExecutionGuard(
            CertificationRepository certifications,
            BuildSessionRepository buildSessions,
            WorkOrderRepository workOrders,
            WorkerProtocolArtifacts workerProtocolArtifacts,
            CandidateVersionRepository candidates,
            VerificationRunRepository verificationRuns,
            VerificationEvidenceValidator verificationEvidence,
            VerificationActionEvidenceOwner verificationOwner,
            VerificationDispatchIntentRepository verificationIntents,
            AgentPackCertificationPolicyCatalog trustedPolicy,
            Clock clock) {
        this.certifications = Objects.requireNonNull(certifications, "certifications");
        this.buildSessions = Objects.requireNonNull(buildSessions, "buildSessions");
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
        this.workerProtocolArtifacts = Objects.requireNonNull(workerProtocolArtifacts, "workerProtocolArtifacts");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.verificationRuns = Objects.requireNonNull(verificationRuns, "verificationRuns");
        this.verificationEvidence = Objects.requireNonNull(verificationEvidence, "verificationEvidence");
        this.verificationOwner = Objects.requireNonNull(verificationOwner, "verificationOwner");
        this.verificationIntents = Objects.requireNonNull(verificationIntents, "verificationIntents");
        this.trustedPolicy = Objects.requireNonNull(trustedPolicy, "trustedPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PreExecutionDecision check(
            ActionProposal proposal,
            ActionDefinition definition,
            ExecutionContext context,
            PolicyDecision policyDecision) {
        if (!CertificationIssueAction.ACTION_ID.equals(proposal.actionId())
                || !CertificationIssueAction.ACTION_ID.equals(definition.actionId())) {
            return deny("CERTIFICATION_ACTION_MISMATCH", "guard only accepts Certification issuance Actions");
        }
        CertificationIssueInput input;
        TenantId tenantId;
        try {
            input = CertificationIssueInput.from(proposal.input());
            tenantId = new TenantId(context.tenantId());
        } catch (IllegalArgumentException exception) {
            return deny("CERTIFICATION_SCOPE_INVALID", exception.getMessage());
        }
        if (!CertificationIssueAction.RESOURCE_TYPE.equals(context.metadata().get("resource.type"))
                || !input.certificationId().value().equals(context.metadata().get("resource.id"))) {
            return deny("CERTIFICATION_SCOPE_INVALID", "trusted Certification resource does not match");
        }
        var certification = certifications.find(tenantId, input.certificationId()).orElse(null);
        if (certification == null) {
            return deny("CERTIFICATION_RESOURCE_NOT_FOUND", "Certification is not visible");
        }
        CertificationInputLock lock = certification.inputLock();
        if (certification.status() != CertificationStatus.REQUESTED
                || certification.version() != input.expectedCertificationVersion()) {
            return deny("CERTIFICATION_STALE", "Certification is no longer the requested version");
        }
        if (!lock.tenantId().equals(tenantId)
                || !certification.inputLockArtifact().hash().equals(input.inputLockManifestHash())) {
            return deny("CERTIFICATION_INPUT_LOCK_MISMATCH", "Certification exact input lock does not match");
        }
        if (!trustedPolicy.matches(lock)) {
            return deny("CERTIFICATION_POLICY_MISMATCH", "trusted Agent Pack policy does not admit the lock");
        }

        var session = buildSessions.find(tenantId, lock.buildSessionId()).orElse(null);
        if (session == null
                || !ProductLineId.AGENT_PACK.equals(session.productLineId())
                || session.status() != BuildSessionStatus.CERTIFYING
                || session.currentPhase() != BuildSessionPhase.CERTIFY
                || session.currentCandidateId().filter(lock.candidateId()::equals).isEmpty()
                || session.currentCandidateHash().filter(lock.candidateHash()::equals).isEmpty()) {
            return deny("BUILD_SESSION_NOT_CERTIFIABLE", "BuildSession is not bound to this Agent Pack candidate");
        }
        if (session.cancellationRequestedAt().isPresent() || !clock.instant().isBefore(session.deadlineAt())) {
            return deny("BUILD_SESSION_NOT_CERTIFIABLE", "BuildSession is cancelled or past its deadline");
        }

        WorkOrder workOrder = workOrders.find(tenantId, lock.generationWorkOrderId()).orElse(null);
        if (!matches(workOrder, lock)) {
            return deny("CERTIFICATION_WORK_ORDER_MISMATCH", "generation WorkOrder does not match the lock");
        }
        CodingWorkerInputManifest generationInput;
        try {
            generationInput = workerProtocolArtifacts.readInput(workOrder);
            workerProtocolArtifacts.exact(
                    tenantId, lock.policySnapshot().reference(), lock.policySnapshot().hash());
        } catch (RuntimeException invalidArtifact) {
            return deny("CERTIFICATION_GENERATION_INPUT_INVALID", "generation input artifacts are invalid");
        }
        if (!matches(generationInput, lock) || !trustedPolicy.matchesGeneration(lock, generationInput)) {
            return deny("CERTIFICATION_GENERATION_INPUT_MISMATCH", "generation input does not match the lock");
        }

        CandidateVersion candidate = candidates.find(tenantId, lock.candidateId()).orElse(null);
        if (!matches(candidate, lock)) {
            return deny("CERTIFICATION_CANDIDATE_MISMATCH", "Candidate does not match the exact input lock");
        }

        VerificationRun verification = verificationRuns.find(tenantId, lock.verificationRunId()).orElse(null);
        if (!matches(verification, lock)) {
            return deny("CERTIFICATION_VERIFICATION_NOT_ELIGIBLE", "Verification evidence is not eligible");
        }
        try {
            if (!verificationEvidence.isReviewEligible(verification)) {
                return deny("CERTIFICATION_VERIFICATION_NOT_ELIGIBLE", "Verification evidence is not eligible");
            }
        } catch (RuntimeException corruptEvidence) {
            return deny("CERTIFICATION_VERIFICATION_EVIDENCE_INVALID", "Verification evidence could not be trusted");
        }
        VerificationActionEvidenceOwner.Assessment owner;
        VerificationDispatchIntent intent;
        try {
            owner = verificationOwner.assess(verification);
            intent = verificationIntents.findLatest(tenantId, lock.verificationRunId()).orElse(null);
        } catch (RuntimeException corruptOwner) {
            return deny("CERTIFICATION_VERIFICATION_OWNER_INVALID", "Verification Action owner could not be trusted");
        }
        if (owner.status() != VerificationActionEvidenceOwner.Status.CANONICAL_SUCCEEDED
                || intent == null
                || !intent.tenantId().equals(tenantId)
                || !intent.verificationRunId().equals(lock.verificationRunId())
                || !intent.candidateId().equals(lock.candidateId())
                || !intent.actionRunId().equals(lock.verificationActionRunId())) {
            return deny("CERTIFICATION_VERIFICATION_OWNER_INVALID", "Verification Action owner is not canonical");
        }
        return PreExecutionDecision.allow();
    }

    private static boolean matches(WorkOrder order, CertificationInputLock lock) {
        return order != null
                && order.tenantId().equals(lock.tenantId())
                && order.workOrderId().equals(lock.generationWorkOrderId())
                && order.buildSessionId().equals(lock.buildSessionId())
                && order.inputArtifactManifestRef().equals(lock.generationInputManifest().reference())
                && order.inputManifestHash().equals(lock.generationInputManifest().hash())
                && order.policySnapshotRef().equals(lock.policySnapshot().reference());
    }

    private static boolean matches(CodingWorkerInputManifest input, CertificationInputLock lock) {
        return input.workOrderId().equals(lock.generationWorkOrderId())
                && input.buildSessionId().equals(lock.buildSessionId())
                && input.dependencyLockRef().equals(lock.dependencyLock().reference())
                && input.dependencyLockHash().equals(lock.dependencyLock().hash())
                && input.toolchainLockRef().equals(lock.toolchainLock().reference())
                && input.toolchainLockHash().equals(lock.toolchainLock().hash())
                && input.apiSignatureIndexRef().equals(lock.apiSignatureIndex().reference())
                && input.apiSignatureIndexHash().equals(lock.apiSignatureIndex().hash())
                && input.productContractBundleRef().equals(lock.productContractBundle().reference())
                && input.productContractBundleHash().equals(lock.productContractBundle().hash())
                && input.gateProfile().equals(lock.gateProfile())
                && input.sourceLockAlgorithmId().equals(lock.sourceLockAlgorithmId());
    }

    private static boolean matches(CandidateVersion candidate, CertificationInputLock lock) {
        return candidate != null
                && candidate.tenantId().equals(lock.tenantId())
                && candidate.buildSessionId().equals(lock.buildSessionId())
                && candidate.candidateId().equals(lock.candidateId())
                && candidate.createdByWorkOrderId().equals(lock.generationWorkOrderId())
                && candidate.sourceManifestRef().equals(lock.sourceManifest().reference())
                && candidate.sourceHash().equals(lock.candidateHash())
                && candidate.dependencyLockRef().equals(lock.dependencyLock().reference())
                && candidate.dependencyLockHash().equals(lock.dependencyLock().hash())
                && candidate.toolchainLockRef().equals(lock.toolchainLock().reference())
                && candidate.toolchainLockHash().equals(lock.toolchainLock().hash())
                && candidate.status() == CandidateVersionStatus.GENERATED;
    }

    private static boolean matches(VerificationRun verification, CertificationInputLock lock) {
        return verification != null
                && verification.tenantId().equals(lock.tenantId())
                && verification.buildSessionId().equals(lock.buildSessionId())
                && verification.verificationRunId().equals(lock.verificationRunId())
                && verification.candidateId().equals(lock.candidateId())
                && verification.candidateHash().equals(lock.candidateHash())
                && verification.toolchainLockHash().equals(lock.toolchainLock().hash())
                && verification.gateProfile().equals(lock.gateProfile())
                && verification.fixtureSetHash().equals(lock.verificationFixtureSetHash())
                && verification.status() == VerificationRunStatus.PASSED
                && verification.disposition().filter(VerificationDisposition.REVIEW_ELIGIBLE::equals).isPresent()
                && verification.resultManifestRef()
                        .filter(lock.verificationResultManifest().reference()::equals)
                        .isPresent()
                && verification.resultManifestHash()
                        .filter(lock.verificationResultManifest().hash()::equals)
                        .isPresent()
                && !VerificationRun.LEGACY_RESULT_MANIFEST_HASH.equals(
                        verification.resultManifestHash().orElse(null));
    }

    private static PreExecutionDecision deny(String code, String reason) {
        return PreExecutionDecision.deny(code, reason);
    }
}
