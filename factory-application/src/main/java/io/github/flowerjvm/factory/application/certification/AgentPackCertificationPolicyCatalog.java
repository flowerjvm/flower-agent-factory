package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.verification.ActionBackedVerificationRunLauncher;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import java.util.List;
import java.util.Objects;

/**
 * Code-owned exact certification profiles, selected from canonical generation provenance.
 * The legacy policy and its serialized artifacts are unchanged; the maintenance profile cannot
 * borrow a PR4 result or be admitted through a compatibility constructor without provenance.
 */
public final class AgentPackCertificationPolicyCatalog {
    private final List<AgentPackCertificationPolicy> policies;
    private final WorkOrderRepository workOrders;
    private final WorkerProtocolArtifacts artifacts;

    private AgentPackCertificationPolicyCatalog(
            List<AgentPackCertificationPolicy> policies,
            WorkOrderRepository workOrders,
            WorkerProtocolArtifacts artifacts) {
        this.policies = List.copyOf(policies);
        this.workOrders = workOrders;
        this.artifacts = artifacts;
    }

    /** Compatibility for existing single-policy callers; not a new-profile production binding. */
    public static AgentPackCertificationPolicyCatalog singleton(AgentPackCertificationPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (isMaintenance(policy)) {
            throw new IllegalArgumentException("maintenance certification requires generation provenance");
        }
        return new AgentPackCertificationPolicyCatalog(List.of(policy), null, null);
    }

    /** Production wiring still issues internal certifications, not externally signed attestations. */
    public static AgentPackCertificationPolicyCatalog production(
            ContentHash verificationFixtureSetHash,
            String factoryVersion,
            String flowerVersion,
            String actionRuntimeVersion,
            WorkOrderRepository workOrders,
            WorkerProtocolArtifacts artifacts) {
        return new AgentPackCertificationPolicyCatalog(List.of(
                policy(AgentPackProductContract.lock(), ActionBackedVerificationRunLauncher.GATE_PROFILE,
                        verificationFixtureSetHash, factoryVersion, flowerVersion, actionRuntimeVersion),
                policy(MaintenanceInvestigationProductContract.lock(),
                        MaintenanceInvestigationProductContract.GATE_PROFILE,
                        verificationFixtureSetHash, factoryVersion, flowerVersion, actionRuntimeVersion)),
                Objects.requireNonNull(workOrders, "workOrders"),
                Objects.requireNonNull(artifacts, "artifacts"));
    }

    /** Caller must first read/re-hash the exact generation manifest from its trusted WorkOrder. */
    public AgentPackCertificationPolicy policyForGeneration(CodingWorkerInputManifest input) {
        Objects.requireNonNull(input, "input");
        CertificationArtifactLock product = new CertificationArtifactLock(
                input.productContractBundleRef(), input.productContractBundleHash());
        AgentPackCertificationPolicy policy = policies.stream()
                .filter(value -> value.productContractBundle().equals(product))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unrecognized certification product contract"));
        if (!policy.gateProfile().equals(input.gateProfile())
                || !policy.sourceLockAlgorithmId().equals(input.sourceLockAlgorithmId())
                || (isMaintenance(policy) && (!MaintenanceInvestigationProductContract.apiSignatureIndexLock().equals(
                                new CertificationArtifactLock(input.apiSignatureIndexRef(), input.apiSignatureIndexHash()))
                        || !MaintenanceInvestigationProductContract.requirementTestMatrixLock().equals(
                                new CertificationArtifactLock(input.requirementTestMatrixRef(), input.requirementTestMatrixHash()))))) {
            throw new IllegalArgumentException("generation inputs do not match the exact certification profile");
        }
        return policy;
    }

    /** Pure lock admission, used only with a separately revalidated generation provenance. */
    public boolean admits(CertificationInputLock lock) {
        return lock != null && policies.stream().anyMatch(policy -> policy.matches(lock)
                && (!isMaintenance(policy)
                        || MaintenanceInvestigationProductContract.apiSignatureIndexLock().equals(lock.apiSignatureIndex())));
    }

    /** Pure comparison for JDBC callers that loaded the manifest inside their current transaction. */
    public boolean matchesGeneration(CertificationInputLock lock, CodingWorkerInputManifest input) {
        if (!admits(lock) || input == null) return false;
        try {
            return policyForGeneration(input).matches(lock)
                    && input.workOrderId().equals(lock.generationWorkOrderId())
                    && input.buildSessionId().equals(lock.buildSessionId())
                    && input.dependencyLockRef().equals(lock.dependencyLock().reference())
                    && input.dependencyLockHash().equals(lock.dependencyLock().hash())
                    && input.toolchainLockRef().equals(lock.toolchainLock().reference())
                    && input.toolchainLockHash().equals(lock.toolchainLock().hash())
                    && input.apiSignatureIndexRef().equals(lock.apiSignatureIndex().reference())
                    && input.apiSignatureIndexHash().equals(lock.apiSignatureIndex().hash());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** Artifact bytes which the selected new profile requires in addition to the generation lock. */
    public List<CertificationArtifactLock> requiredGenerationArtifacts(CodingWorkerInputManifest input) {
        AgentPackCertificationPolicy policy = policyForGeneration(input);
        return isMaintenance(policy) ? List.of(policy.productContractBundle(),
                MaintenanceInvestigationProductContract.apiSignatureIndexLock(),
                MaintenanceInvestigationProductContract.requirementTestMatrixLock()) : List.of();
    }

    /** Read-only, fail-closed provenance check shared by Action admission, preflight and dispatch. */
    public boolean matches(CertificationInputLock lock) {
        if (!admits(lock)) return false;
        if (workOrders == null) return true; // Existing single-policy compatibility only.
        try {
            var order = workOrders.find(lock.tenantId(), lock.generationWorkOrderId()).orElse(null);
            if (order == null || !order.tenantId().equals(lock.tenantId())
                    || !order.workOrderId().equals(lock.generationWorkOrderId())
                    || !order.buildSessionId().equals(lock.buildSessionId())
                    || !BuildSessionPhase.GENERATE_CANDIDATE.id().equals(order.phase())
                    || !order.inputArtifactManifestRef().equals(lock.generationInputManifest().reference())
                    || !order.inputManifestHash().equals(lock.generationInputManifest().hash())
                    || !order.policySnapshotRef().equals(lock.policySnapshot().reference())) return false;
            CodingWorkerInputManifest input = artifacts.readInput(order);
            if (!matchesGeneration(lock, input)) return false;
            for (CertificationArtifactLock required : requiredGenerationArtifacts(input)) {
                artifacts.exact(lock.tenantId(), required.reference(), required.hash());
            }
            return true;
        } catch (RuntimeException unavailableOrInvalid) {
            return false;
        }
    }

    private static boolean isMaintenance(AgentPackCertificationPolicy policy) {
        return MaintenanceInvestigationProductContract.lock().equals(policy.productContractBundle())
                || MaintenanceInvestigationProductContract.GATE_PROFILE.equals(policy.gateProfile());
    }

    private static AgentPackCertificationPolicy policy(
            CertificationArtifactLock product, String gate, ContentHash fixtures,
            String factoryVersion, String flowerVersion, String actionRuntimeVersion) {
        return new AgentPackCertificationPolicy(product, gate, fixtures,
                CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID, "internal",
                factoryVersion, flowerVersion, actionRuntimeVersion);
    }
}
