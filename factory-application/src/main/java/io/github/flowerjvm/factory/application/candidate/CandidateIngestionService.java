package io.github.flowerjvm.factory.application.candidate;

import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.application.work.WorkerRunRecord;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.worker.CandidateOutputLock;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerResultManifest;
import io.github.flowerjvm.factory.contracts.worker.PortableRelativePath;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Validates every immutable Worker output before Action completion, then inserts a CandidateVersion
 * only after the canonical WorkerRun success wins.
 */
public final class CandidateIngestionService {
    private final WorkerProtocolArtifacts artifacts;
    private final CandidateVersionRepository candidates;

    public CandidateIngestionService(
            WorkerProtocolArtifacts artifacts,
            CandidateVersionRepository candidates) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
    }

    public PreparedResult validateAndPromote(
            WorkOrder order,
            WorkerRunRecord workerRun,
            CodingWorkerResultManifest result,
            Instant trustedReceivedAt) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(workerRun, "workerRun");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(trustedReceivedAt, "trustedReceivedAt");
        if (!order.tenantId().equals(workerRun.tenantId())
                || !order.workOrderId().equals(workerRun.workOrderId())
                || !order.buildSessionId().equals(workerRun.buildSessionId())
                || !result.workOrderId().equals(order.workOrderId())
                || !result.workerRunId().equals(workerRun.workerRunId())
                || !result.operationId().equals(workerRun.operationId())
                || !result.outputSchemaId().equals(order.expectedOutputSchemaId())
                || !result.outputSchemaVersion().equals(order.expectedOutputSchemaVersion())) {
            throw new IllegalArgumentException("WORKER_RESULT_IDENTITY_MISMATCH");
        }
        result.transcriptArtifactRef().ifPresent(reference -> artifacts.exact(
                order.tenantId(), reference, result.transcriptHash().orElseThrow()));

        CodingWorkerInputManifest input = artifacts.readInput(order);
        validateInputIdentity(order, input);
        validateTrustedInputs(order, input);

        boolean generation = BuildSessionPhase.GENERATE_CANDIDATE.id().equals(order.phase());
        if (generation != result.candidateOutput().isPresent()) {
            throw new IllegalArgumentException("WORKER_RESULT_CANDIDATE_SHAPE_MISMATCH");
        }
        if (result.candidateOutput().isEmpty()) {
            artifacts.exact(order.tenantId(), result.primaryArtifactRef(), result.primaryResultHash());
            return new PreparedResult(order, workerRun, result, Optional.empty(), trustedReceivedAt);
        }

        CandidateOutputLock output = result.candidateOutput().orElseThrow();
        if (!output.dependencyLockRef().equals(input.dependencyLockRef())
                || !output.dependencyLockHash().equals(input.dependencyLockHash())
                || !output.toolchainLockRef().equals(input.toolchainLockRef())
                || !output.toolchainLockHash().equals(input.toolchainLockHash())) {
            throw new IllegalArgumentException("WORKER_RESULT_INPUT_LOCK_MISMATCH");
        }
        CandidateSourceManifest source = artifacts.readCandidateSource(
                order.tenantId(), output.sourceManifestRef(), output.sourceManifestHash());
        if (!source.candidateId().equals(output.candidateId())
                || !source.buildSessionId().equals(order.buildSessionId())
                || !source.candidateHash().equals(output.candidateHash())
                || !source.sourceLockAlgorithmId().equals(input.sourceLockAlgorithmId())) {
            throw new IllegalArgumentException("WORKER_CANDIDATE_MANIFEST_MISMATCH");
        }
        validateCandidateScope(order, source);
        if (source.files().isEmpty() || !sourceTreeHash(source).equals(source.candidateHash())) {
            throw new IllegalArgumentException("WORKER_CANDIDATE_SOURCE_HASH_MISMATCH");
        }
        source.files().forEach(file -> {
            var artifact = artifacts.exact(order.tenantId(), file.artifactRef(), file.contentHash());
            if (artifact.content().length != file.sizeBytes()) {
                throw new IllegalArgumentException("WORKER_CANDIDATE_FILE_SIZE_MISMATCH");
            }
        });
        validateRepairLineage(order, input, output, source);

        CandidateVersion candidate = new CandidateVersion(
                output.candidateId(),
                order.tenantId(),
                order.buildSessionId(),
                output.parentCandidateId(),
                output.sourceManifestRef(),
                output.candidateHash(),
                output.dependencyLockRef(),
                output.dependencyLockHash(),
                output.toolchainLockRef(),
                output.toolchainLockHash(),
                CandidateVersionStatus.GENERATED,
                order.workOrderId(),
                trustedReceivedAt);
        return new PreparedResult(order, workerRun, result, Optional.of(candidate), trustedReceivedAt);
    }

    private static void validateCandidateScope(WorkOrder order, CandidateSourceManifest source) {
        Set<String> scopes = java.util.stream.Stream.concat(
                        order.allowedReadPaths().stream(), order.allowedWritePaths().stream())
                .map(PortableRelativePath::require)
                .map(PortableRelativePath::caseFold)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean outsideScope = scopes.isEmpty() || source.files().stream().anyMatch(file -> {
            String candidatePath = PortableRelativePath.caseFold(file.path());
            return scopes.stream().noneMatch(scope ->
                    candidatePath.equals(scope) || candidatePath.startsWith(scope + "/"));
        });
        if (outsideScope) {
            throw new IllegalArgumentException("WORKER_CANDIDATE_PATH_OUTSIDE_SCOPE");
        }
    }

    /** Called only after WorkerCompletionService reports the canonical Action/Worker success. */
    public Optional<CandidateVersion> persistAfterCanonicalSuccess(
            PreparedResult prepared, WorkerRunRecord canonicalWorkerRun) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(canonicalWorkerRun, "canonicalWorkerRun");
        if (canonicalWorkerRun.status() != WorkerRunStatus.SUCCEEDED
                || !canonicalWorkerRun.workerRunId().equals(prepared.workerRun().workerRunId())
                || !canonicalWorkerRun.resultArtifactManifestRef()
                        .equals(Optional.of(prepared.result().primaryArtifactRef()))
                || !canonicalWorkerRun.resultHash()
                        .equals(Optional.of(prepared.result().primaryResultHash()))) {
            throw new IllegalStateException("canonical WorkerRun does not own the prepared result");
        }
        if (prepared.candidate().isEmpty()) {
            return Optional.empty();
        }
        CandidateVersion candidate = prepared.candidate().orElseThrow();
        var existing = candidates.findByBuildSessionAndWorkOrder(
                candidate.tenantId(), candidate.buildSessionId(), candidate.createdByWorkOrderId());
        if (existing.isPresent()) {
            requireSameCandidate(existing.orElseThrow(), candidate);
            return existing;
        }
        try {
            candidates.create(candidate);
            return Optional.of(candidate);
        } catch (RuntimeException createRace) {
            var winner = candidates.findByBuildSessionAndWorkOrder(
                    candidate.tenantId(), candidate.buildSessionId(), candidate.createdByWorkOrderId());
            if (winner.isEmpty()) {
                throw createRace;
            }
            requireSameCandidate(winner.orElseThrow(), candidate);
            return winner;
        }
    }

    private void validateTrustedInputs(WorkOrder order, CodingWorkerInputManifest input) {
        artifacts.exact(order.tenantId(), input.skillArtifactRef(), input.skillHash());
        artifacts.exact(order.tenantId(), input.dependencyLockRef(), input.dependencyLockHash());
        artifacts.exact(order.tenantId(), input.toolchainLockRef(), input.toolchainLockHash());
        artifacts.exact(order.tenantId(), input.apiSignatureIndexRef(), input.apiSignatureIndexHash());
        artifacts.exact(
                order.tenantId(), input.productContractBundleRef(), input.productContractBundleHash());
        artifacts.exact(
                order.tenantId(), input.requirementTestMatrixRef(), input.requirementTestMatrixHash());
        input.repairLock().ifPresent(repair -> {
            artifacts.exact(order.tenantId(), repair.findingManifestRef(), repair.findingManifestHash());
        });
    }

    private static void validateInputIdentity(WorkOrder order, CodingWorkerInputManifest input) {
        if (!input.workOrderId().equals(order.workOrderId())
                || !input.buildSessionId().equals(order.buildSessionId())) {
            throw new IllegalArgumentException("WORKER_INPUT_MANIFEST_IDENTITY_MISMATCH");
        }
    }

    private void validateRepairLineage(
            WorkOrder order,
            CodingWorkerInputManifest input,
            CandidateOutputLock output,
            CandidateSourceManifest source) {
        if (order.candidateId().isPresent() != input.repairLock().isPresent()) {
            throw new IllegalArgumentException("WORKER_REPAIR_INPUT_SHAPE_MISMATCH");
        }
        if (input.repairLock().isPresent()) {
            var repair = input.repairLock().orElseThrow();
            if (order.candidateId().filter(repair.baseCandidateId()::equals).isEmpty()
                    || output.parentCandidateId().filter(repair.baseCandidateId()::equals).isEmpty()) {
                throw new IllegalArgumentException("WORKER_REPAIR_LINEAGE_MISMATCH");
            }
            CandidateVersion base = candidates.find(order.tenantId(), repair.baseCandidateId())
                    .orElseThrow(() -> new IllegalArgumentException("WORKER_REPAIR_BASE_NOT_FOUND"));
            if (!base.buildSessionId().equals(order.buildSessionId())
                    || !base.sourceHash().equals(repair.baseCandidateHash())) {
                throw new IllegalArgumentException("WORKER_REPAIR_BASE_HASH_MISMATCH");
            }
            CandidateSourceManifest baseSource = artifacts.readCanonicalCandidateSource(
                    order.tenantId(), base.sourceManifestRef());
            if (!baseSource.candidateId().equals(base.candidateId())
                    || !baseSource.buildSessionId().equals(base.buildSessionId())
                    || !baseSource.candidateHash().equals(base.sourceHash())
                    || !baseSource.sourceLockAlgorithmId().equals(input.sourceLockAlgorithmId())
                    || !sourceTreeHash(baseSource).equals(baseSource.candidateHash())) {
                throw new IllegalArgumentException("WORKER_REPAIR_BASE_MANIFEST_MISMATCH");
            }
            requireOnlyAllowedChanges(
                    baseSource, source, new HashSet<>(repair.allowedChangedPaths()));
        } else if (output.parentCandidateId().isPresent()) {
            throw new IllegalArgumentException("non-repair output must not invent a parent candidate");
        }
    }

    private static void requireOnlyAllowedChanges(
            CandidateSourceManifest base,
            CandidateSourceManifest next,
            Set<String> allowedChangedPaths) {
        Map<String, io.github.flowerjvm.factory.contracts.artifact.ContentHash> before = new HashMap<>();
        base.files().forEach(file -> before.put(file.path(), file.contentHash()));
        Map<String, io.github.flowerjvm.factory.contracts.artifact.ContentHash> after = new HashMap<>();
        next.files().forEach(file -> after.put(file.path(), file.contentHash()));
        Set<String> paths = new HashSet<>(before.keySet());
        paths.addAll(after.keySet());
        boolean outsideGrant = paths.stream()
                .filter(path -> !Objects.equals(before.get(path), after.get(path)))
                .anyMatch(path -> !allowedChangedPaths.contains(path));
        if (outsideGrant) {
            throw new IllegalArgumentException("WORKER_REPAIR_CHANGED_PATH_OUTSIDE_GRANT");
        }
    }

    private static io.github.flowerjvm.factory.contracts.artifact.ContentHash sourceTreeHash(
            CandidateSourceManifest manifest) {
        String material = manifest.files().stream()
                .sorted(java.util.Comparator.comparing(
                        io.github.flowerjvm.factory.contracts.verification.CandidateSourceEntry::path))
                .map(file -> file.path() + "\t" + file.contentHash().sha256())
                .collect(java.util.stream.Collectors.joining("\n"));
        try {
            return new io.github.flowerjvm.factory.contracts.artifact.ContentHash(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(material.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    private static void requireSameCandidate(CandidateVersion current, CandidateVersion expected) {
        if (!current.equals(expected)) {
            throw new IllegalStateException("CANDIDATE_WORK_ORDER_RESULT_CONFLICT");
        }
    }

    public record PreparedResult(
            WorkOrder workOrder,
            WorkerRunRecord workerRun,
            CodingWorkerResultManifest result,
            Optional<CandidateVersion> candidate,
            Instant trustedReceivedAt) {
        public PreparedResult {
            Objects.requireNonNull(workOrder, "workOrder");
            Objects.requireNonNull(workerRun, "workerRun");
            Objects.requireNonNull(result, "result");
            candidate = Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(trustedReceivedAt, "trustedReceivedAt");
        }
    }
}
