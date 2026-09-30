package io.github.flowerjvm.factory.contracts.referenceassembly;

import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import java.util.Objects;

/** Concrete allow-list contract for one Agent Pack inside the reference host fixture. */
public record ReferenceAssemblyConsumerContract(
        String schemaVersion,
        ProductLineId productLineId,
        String contractId,
        String contractVersion,
        String requiredComponentRole,
        String requiredCertificationProfile,
        CertificationArtifactLock requiredAgentProductContract,
        CertificationArtifactLock requiredApiSignatureIndex,
        CertificationArtifactLock requiredHostFixture,
        String requiredFlowerVersion,
        String requiredActionRuntimeVersion,
        String assemblyAlgorithmId) {

    public static final String SCHEMA_VERSION = "factory.reference-assembly-consumer-contract.v1";
    public static final String CONTRACT_ID = "reference-assembly-consumer";
    public static final String CONTRACT_VERSION = "1.0.0";
    public static final String MAINTENANCE_CONTRACT_VERSION = "2.0.0";
    public static final String REQUIRED_CERTIFICATION_PROFILE = "internal";
    public static final String REQUIRED_FLOWER_VERSION = "0.1.3";
    public static final String REQUIRED_ACTION_RUNTIME_VERSION = "0.3.3";
    public static final String ASSEMBLY_ALGORITHM_ID = "factory.reference-assembly-manifest.v1";

    public ReferenceAssemblyConsumerContract {
        schemaVersion = ReferenceAssemblyContractValues.requireSchema(
                schemaVersion, SCHEMA_VERSION, "Reference Assembly consumer contract schemaVersion");
        productLineId = ReferenceAssemblyContractValues.requireProductLine(productLineId);
        if (!CONTRACT_ID.equals(contractId)) {
            throw new IllegalArgumentException("unsupported Reference Assembly consumer contractId");
        }
        contractVersion = ReferenceAssemblyContractValues.requireResolvedVersion(
                contractVersion, "contractVersion");
        if (!CONTRACT_VERSION.equals(contractVersion) && !MAINTENANCE_CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("unsupported Reference Assembly consumer contractVersion");
        }
        if (!ReferenceAssemblyRequirement.COMPONENT_ROLE.equals(requiredComponentRole)) {
            throw new IllegalArgumentException(
                    "Reference Assembly consumer contract requires embedded-agent-pack role");
        }
        requiredCertificationProfile = ReferenceAssemblyContractValues.requireStableId(
                requiredCertificationProfile, "requiredCertificationProfile", 128);
        if (!REQUIRED_CERTIFICATION_PROFILE.equals(requiredCertificationProfile)) {
            throw new IllegalArgumentException("unsupported Reference Assembly certification profile");
        }
        Objects.requireNonNull(requiredAgentProductContract, "requiredAgentProductContract");
        Objects.requireNonNull(requiredApiSignatureIndex, "requiredApiSignatureIndex");
        Objects.requireNonNull(requiredHostFixture, "requiredHostFixture");
        requiredFlowerVersion = ReferenceAssemblyContractValues.requireResolvedVersion(
                requiredFlowerVersion, "requiredFlowerVersion");
        if (!REQUIRED_FLOWER_VERSION.equals(requiredFlowerVersion)) {
            throw new IllegalArgumentException("unsupported Reference Assembly Flower version");
        }
        requiredActionRuntimeVersion = ReferenceAssemblyContractValues.requireResolvedVersion(
                requiredActionRuntimeVersion, "requiredActionRuntimeVersion");
        if (!REQUIRED_ACTION_RUNTIME_VERSION.equals(requiredActionRuntimeVersion)) {
            throw new IllegalArgumentException("unsupported Reference Assembly Action Runtime version");
        }
        assemblyAlgorithmId = ReferenceAssemblyContractValues.requireStableId(
                assemblyAlgorithmId, "assemblyAlgorithmId", 128);
        if (!ASSEMBLY_ALGORITHM_ID.equals(assemblyAlgorithmId)) {
            throw new IllegalArgumentException("unsupported Reference Assembly algorithm");
        }
    }
}
