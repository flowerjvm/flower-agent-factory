package io.github.flowerjvm.factory.contracts.referenceassembly;

import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import java.util.Objects;

/** Canonical manifest graph assembled from one exact certified Agent Pack. */
public record ReferenceAssemblyManifest(
        String schemaVersion,
        ProductLineId productLineId,
        String assemblyAlgorithmId,
        CertificationArtifactLock requirement,
        CertificationArtifactLock consumerContract,
        CertificationArtifactLock hostFixture,
        CertificationArtifactLock policySnapshot,
        CertifiedAgentComponentRef component) {

    public static final String SCHEMA_VERSION = "factory.reference-assembly-manifest.v1";

    public ReferenceAssemblyManifest {
        schemaVersion = ReferenceAssemblyContractValues.requireSchema(
                schemaVersion, SCHEMA_VERSION, "Reference Assembly manifest schemaVersion");
        productLineId = ReferenceAssemblyContractValues.requireProductLine(productLineId);
        assemblyAlgorithmId = ReferenceAssemblyContractValues.requireStableId(
                assemblyAlgorithmId, "assemblyAlgorithmId", 128);
        if (!ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID.equals(assemblyAlgorithmId)) {
            throw new IllegalArgumentException("unsupported Reference Assembly manifest algorithm");
        }
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(consumerContract, "consumerContract");
        Objects.requireNonNull(hostFixture, "hostFixture");
        Objects.requireNonNull(policySnapshot, "policySnapshot");
        component = ReferenceAssemblyContractValues.requireEmbeddedAgentPack(component);
    }
}
