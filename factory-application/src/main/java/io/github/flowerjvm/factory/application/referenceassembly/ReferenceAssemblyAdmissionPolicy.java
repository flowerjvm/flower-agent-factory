package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import java.util.Objects;

/** Code/config-owned exact allow-list for the concrete Reference Assembly v1 line. */
public record ReferenceAssemblyAdmissionPolicy(
        CertificationArtifactLock approvedConsumerContract,
        CertificationArtifactLock approvedHostFixture,
        CertificationArtifactLock approvedPolicySnapshot,
        CertificationArtifactLock expectedAgentProductContract,
        CertificationArtifactLock expectedApiSignatureIndex,
        String requiredComponentRole,
        String requiredCertificationProfile,
        String requiredFlowerVersion,
        String requiredActionRuntimeVersion,
        String requiredSourceLockAlgorithmId,
        String assemblyAlgorithmId) {

    public ReferenceAssemblyAdmissionPolicy {
        Objects.requireNonNull(approvedConsumerContract, "approvedConsumerContract");
        Objects.requireNonNull(approvedHostFixture, "approvedHostFixture");
        Objects.requireNonNull(approvedPolicySnapshot, "approvedPolicySnapshot");
        Objects.requireNonNull(expectedAgentProductContract, "expectedAgentProductContract");
        Objects.requireNonNull(expectedApiSignatureIndex, "expectedApiSignatureIndex");
        requireExact(requiredComponentRole, ReferenceAssemblyRequirement.COMPONENT_ROLE,
                "requiredComponentRole");
        requireExact(requiredCertificationProfile,
                ReferenceAssemblyConsumerContract.REQUIRED_CERTIFICATION_PROFILE,
                "requiredCertificationProfile");
        requireExact(requiredFlowerVersion,
                ReferenceAssemblyConsumerContract.REQUIRED_FLOWER_VERSION,
                "requiredFlowerVersion");
        requireExact(requiredActionRuntimeVersion,
                ReferenceAssemblyConsumerContract.REQUIRED_ACTION_RUNTIME_VERSION,
                "requiredActionRuntimeVersion");
        requireStableId(requiredSourceLockAlgorithmId, "requiredSourceLockAlgorithmId");
        requireExact(assemblyAlgorithmId,
                ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID,
                "assemblyAlgorithmId");
    }

    private static void requireExact(String value, String expected, String name) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(name + " is not the supported Reference Assembly v1 value");
        }
    }

    private static void requireStableId(String value, String name) {
        if (value == null
                || value.isBlank()
                || value.length() > 128
                || value.chars().anyMatch(Character::isISOControl)
                || !value.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")) {
            throw new IllegalArgumentException(name + " must be a bounded exact lowercase stable id");
        }
    }
}
