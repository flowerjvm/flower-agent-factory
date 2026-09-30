package io.github.flowerjvm.factory.contracts.worker;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, bounded instruction issued to a coding worker.
 *
 * <p>An external dispatch operation belongs to a {@code WorkerRun} attempt, not to this logical
 * instruction. Transport redelivery therefore reuses {@link #logicalIdempotencyKey()} while a new
 * logical retry creates a new WorkOrder or key as required by policy.
 *
 * <p>The referenced and hashed input manifest is the S0 generation/repair lock. It binds the
 * selected Skill, toolchain, API signature index, product contract, gate profile,
 * requirement-test matrix, source-lock algorithm and, for repair, the base candidate and allowed
 * changed paths. Those manifest entries are intentionally not duplicated as mutable row fields.
 */
public record WorkOrder(
        WorkOrderId workOrderId,
        TenantId tenantId,
        BuildSessionId buildSessionId,
        String phase,
        String purpose,
        int revision,
        Optional<WorkOrderId> supersedesWorkOrderId,
        Optional<CandidateId> candidateId,
        Optional<String> baseRevision,
        ArtifactReference instructionArtifactRef,
        ContentHash instructionHash,
        ArtifactReference inputArtifactManifestRef,
        ContentHash inputManifestHash,
        String workspaceRef,
        List<String> allowedReadPaths,
        List<String> allowedWritePaths,
        Set<WorkerCapability> requiredCapabilities,
        String expectedOutputSchemaId,
        String expectedOutputSchemaVersion,
        ArtifactReference policySnapshotRef,
        Instant deadlineAt,
        int maxAttempts,
        String logicalIdempotencyKey,
        WorkOrderCreatorType createdByType,
        String createdByRef,
        Instant createdAt) {

    public WorkOrder {
        Objects.requireNonNull(workOrderId, "workOrderId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        phase = requireText(phase, "phase");
        purpose = requireText(purpose, "purpose");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be at least one");
        }
        supersedesWorkOrderId = Objects.requireNonNull(supersedesWorkOrderId, "supersedesWorkOrderId");
        if (supersedesWorkOrderId.filter(workOrderId::equals).isPresent()) {
            throw new IllegalArgumentException("a WorkOrder must not supersede itself");
        }
        candidateId = Objects.requireNonNull(candidateId, "candidateId");
        baseRevision = requireOptionalText(baseRevision, "baseRevision");
        Objects.requireNonNull(instructionArtifactRef, "instructionArtifactRef");
        Objects.requireNonNull(instructionHash, "instructionHash");
        Objects.requireNonNull(inputArtifactManifestRef, "inputArtifactManifestRef");
        Objects.requireNonNull(inputManifestHash, "inputManifestHash");
        workspaceRef = requireText(workspaceRef, "workspaceRef");
        allowedReadPaths = copyTextList(allowedReadPaths, "allowedReadPaths");
        allowedWritePaths = copyTextList(allowedWritePaths, "allowedWritePaths");
        requiredCapabilities = Set.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
        expectedOutputSchemaId = requireText(expectedOutputSchemaId, "expectedOutputSchemaId");
        expectedOutputSchemaVersion = requireText(expectedOutputSchemaVersion, "expectedOutputSchemaVersion");
        Objects.requireNonNull(policySnapshotRef, "policySnapshotRef");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least one");
        }
        logicalIdempotencyKey = requireText(logicalIdempotencyKey, "logicalIdempotencyKey");
        Objects.requireNonNull(createdByType, "createdByType");
        createdByRef = requireText(createdByRef, "createdByRef");
        Objects.requireNonNull(createdAt, "createdAt");
        if (deadlineAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("deadlineAt must not be before createdAt");
        }
    }

    private static List<String> copyTextList(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        values.forEach(value -> requireText(value, name + " entry"));
        return List.copyOf(values);
    }

    private static Optional<String> requireOptionalText(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(text -> requireText(text, name));
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
