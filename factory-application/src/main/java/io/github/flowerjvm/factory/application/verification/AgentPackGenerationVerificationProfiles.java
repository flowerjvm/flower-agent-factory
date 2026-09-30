package io.github.flowerjvm.factory.application.verification;

import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.certification.AgentPackProductContract;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import java.util.Objects;

/** Selects a code-owned gate from the immutable generation order, never from Worker output. */
public final class AgentPackGenerationVerificationProfiles {
    private final WorkOrderRepository workOrders;
    private final WorkerProtocolArtifacts artifacts;
    private final CandidateVersionRepository candidates;

    public AgentPackGenerationVerificationProfiles(
            WorkOrderRepository workOrders,
            WorkerProtocolArtifacts artifacts,
            CandidateVersionRepository candidates) {
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
    }

    private AgentPackGenerationVerificationProfiles() {
        workOrders = null;
        artifacts = null;
        candidates = null;
    }

    /** Compatibility only: existing constructors can authorize PR4, never the new product gate. */
    public static AgentPackGenerationVerificationProfiles legacyPr4Only() {
        return new AgentPackGenerationVerificationProfiles();
    }

    public String profileFor(CandidateVersion candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (workOrders == null) {
            return ActionBackedVerificationRunLauncher.GATE_PROFILE;
        }
        var order = workOrders.find(candidate.tenantId(), candidate.createdByWorkOrderId())
                .orElseThrow(() -> invalid("generation WorkOrder is missing"));
        if (!order.tenantId().equals(candidate.tenantId())
                || !order.workOrderId().equals(candidate.createdByWorkOrderId())
                || !order.buildSessionId().equals(candidate.buildSessionId())
                || !BuildSessionPhase.GENERATE_CANDIDATE.id().equals(order.phase())) {
            throw invalid("generation WorkOrder identity or phase mismatch");
        }
        var input = artifacts.readInput(order);
        if (!input.workOrderId().equals(order.workOrderId())
                || !input.buildSessionId().equals(candidate.buildSessionId())
                || !input.dependencyLockRef().equals(candidate.dependencyLockRef())
                || !input.dependencyLockHash().equals(candidate.dependencyLockHash())
                || !input.toolchainLockRef().equals(candidate.toolchainLockRef())
                || !input.toolchainLockHash().equals(candidate.toolchainLockHash())
                || !CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID.equals(input.sourceLockAlgorithmId())) {
            throw invalid("generation input identity or candidate locks mismatch");
        }
        if (!candidate.parentCandidateId().equals(order.candidateId())
                || order.candidateId().isPresent() != input.repairLock().isPresent()) {
            throw invalid("generation repair lineage mismatch");
        }
        input.repairLock().ifPresent(repair -> {
            if (order.candidateId().filter(repair.baseCandidateId()::equals).isEmpty()) {
                throw invalid("generation repair base identity mismatch");
            }
            var base = candidates.find(candidate.tenantId(), repair.baseCandidateId())
                    .orElseThrow(() -> invalid("generation repair base is missing"));
            if (!base.tenantId().equals(candidate.tenantId())
                    || !base.candidateId().equals(repair.baseCandidateId())
                    || !base.buildSessionId().equals(candidate.buildSessionId())
                    || !base.sourceHash().equals(repair.baseCandidateHash())) {
                throw invalid("generation repair base lock mismatch");
            }
            artifacts.exact(order.tenantId(), repair.findingManifestRef(), repair.findingManifestHash());
        });

        var contract = new CertificationArtifactLock(
                input.productContractBundleRef(), input.productContractBundleHash());
        final String requiredProfile;
        if (AgentPackProductContract.lock().equals(contract)) {
            requiredProfile = ActionBackedVerificationRunLauncher.GATE_PROFILE;
        } else if (MaintenanceInvestigationProductContract.lock().equals(contract)) {
            requiredProfile = MaintenanceInvestigationProductContract.GATE_PROFILE;
            if (!MaintenanceInvestigationProductContract.apiSignatureIndexLock().equals(
                            new CertificationArtifactLock(input.apiSignatureIndexRef(), input.apiSignatureIndexHash()))
                    || !MaintenanceInvestigationProductContract.requirementTestMatrixLock().equals(
                            new CertificationArtifactLock(
                                    input.requirementTestMatrixRef(), input.requirementTestMatrixHash()))) {
                throw invalid("maintenance API or requirement matrix lock mismatch");
            }
        } else {
            throw invalid("unknown product contract");
        }
        if (!requiredProfile.equals(input.gateProfile())) {
            throw invalid("product contract gate profile mismatch");
        }
        // Rehash every authority-bearing immutable artifact, including the legacy PR4 inputs.
        artifacts.exact(order.tenantId(), input.skillArtifactRef(), input.skillHash());
        artifacts.exact(order.tenantId(), input.dependencyLockRef(), input.dependencyLockHash());
        artifacts.exact(order.tenantId(), input.toolchainLockRef(), input.toolchainLockHash());
        artifacts.exact(order.tenantId(), contract.reference(), contract.hash());
        artifacts.exact(order.tenantId(), input.apiSignatureIndexRef(), input.apiSignatureIndexHash());
        artifacts.exact(order.tenantId(), input.requirementTestMatrixRef(), input.requirementTestMatrixHash());
        return requiredProfile;
    }

    public void requireMatches(CandidateVersion candidate, VerificationRun run) {
        if (!profileFor(candidate).equals(run.gateProfile())) {
            throw invalid("VerificationRun gate profile mismatch");
        }
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("VERIFICATION_GENERATION_PROFILE_INVALID: " + detail);
    }
}
