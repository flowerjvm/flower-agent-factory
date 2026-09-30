package io.github.flowerjvm.factory.contracts.worker;

import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import java.util.Objects;
import java.util.Optional;

/** Strict v1 S0 lock for every authority-bearing input to generation or repair. */
public record CodingWorkerInputManifest(
        String schemaVersion,
        WorkOrderId workOrderId,
        BuildSessionId buildSessionId,
        String skillId,
        String skillVersion,
        ArtifactReference skillArtifactRef,
        ContentHash skillHash,
        ArtifactReference dependencyLockRef,
        ContentHash dependencyLockHash,
        ArtifactReference toolchainLockRef,
        ContentHash toolchainLockHash,
        ArtifactReference apiSignatureIndexRef,
        ContentHash apiSignatureIndexHash,
        ArtifactReference productContractBundleRef,
        ContentHash productContractBundleHash,
        String gateProfile,
        ArtifactReference requirementTestMatrixRef,
        ContentHash requirementTestMatrixHash,
        String sourceLockAlgorithmId,
        Optional<CodingWorkerRepairLock> repairLock) {

    public static final String SCHEMA_VERSION = "factory.coding-worker-input-manifest.v1";

    public CodingWorkerInputManifest {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported Coding Worker input manifest schemaVersion");
        }
        Objects.requireNonNull(workOrderId, "workOrderId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        skillId = requireText(skillId, "skillId", 128);
        skillVersion = requireText(skillVersion, "skillVersion", 64);
        Objects.requireNonNull(skillArtifactRef, "skillArtifactRef");
        Objects.requireNonNull(skillHash, "skillHash");
        Objects.requireNonNull(dependencyLockRef, "dependencyLockRef");
        Objects.requireNonNull(dependencyLockHash, "dependencyLockHash");
        Objects.requireNonNull(toolchainLockRef, "toolchainLockRef");
        Objects.requireNonNull(toolchainLockHash, "toolchainLockHash");
        Objects.requireNonNull(apiSignatureIndexRef, "apiSignatureIndexRef");
        Objects.requireNonNull(apiSignatureIndexHash, "apiSignatureIndexHash");
        Objects.requireNonNull(productContractBundleRef, "productContractBundleRef");
        Objects.requireNonNull(productContractBundleHash, "productContractBundleHash");
        gateProfile = requireText(gateProfile, "gateProfile", 128);
        Objects.requireNonNull(requirementTestMatrixRef, "requirementTestMatrixRef");
        Objects.requireNonNull(requirementTestMatrixHash, "requirementTestMatrixHash");
        sourceLockAlgorithmId = requireText(sourceLockAlgorithmId, "sourceLockAlgorithmId", 128);
        repairLock = Objects.requireNonNull(repairLock, "repairLock");
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
        }
        return value;
    }
}
