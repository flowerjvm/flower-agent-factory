package io.github.flowerjvm.factory.contracts.referenceassembly;

import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import java.util.Objects;

/** Exact, tenant-free input artifact for the concrete reference-assembly ProductLine. */
public record ReferenceAssemblyRequirement(
        String schemaVersion,
        ProductLineId productLineId,
        CertificationArtifactLock consumerContract,
        CertificationArtifactLock hostFixture,
        CertificationArtifactLock policySnapshot,
        CertifiedAgentComponentRef component) {

    public static final String SCHEMA_VERSION = "factory.reference-assembly-requirement.v1";
    public static final ProductLineId PRODUCT_LINE_ID = ProductLineId.REFERENCE_ASSEMBLY;
    public static final String COMPONENT_ROLE = "embedded-agent-pack";

    public ReferenceAssemblyRequirement {
        schemaVersion = ReferenceAssemblyContractValues.requireSchema(
                schemaVersion, SCHEMA_VERSION, "Reference Assembly requirement schemaVersion");
        productLineId = ReferenceAssemblyContractValues.requireProductLine(productLineId);
        Objects.requireNonNull(consumerContract, "consumerContract");
        Objects.requireNonNull(hostFixture, "hostFixture");
        Objects.requireNonNull(policySnapshot, "policySnapshot");
        component = ReferenceAssemblyContractValues.requireEmbeddedAgentPack(component);
    }
}
