package io.github.flowerjvm.factory.contracts.referenceassembly;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import java.util.Objects;

/** Canonical tenant-free release product assembled from exact graph, review, and Action locks. */
public record ReferenceAssemblyReleaseManifest(
        String schemaVersion,
        ProductLineId productLineId,
        String releaseAlgorithmId,
        ReferenceAssemblyId referenceAssemblyId,
        CertificationArtifactLock requirement,
        CertificationArtifactLock consumerContract,
        CertificationArtifactLock hostFixture,
        CertificationArtifactLock policySnapshot,
        CertifiedAgentComponentRef component,
        CertificationArtifactLock assemblyManifest,
        CertificationArtifactLock inspectionReport,
        DecisionPointId releaseDecisionPointId,
        ContentHash releaseSubjectHash,
        String releaseActionRunId) {

    public static final String SCHEMA_VERSION =
            "factory.reference-assembly-release-manifest.v1";
    public static final String RELEASE_ALGORITHM_ID = SCHEMA_VERSION;

    public ReferenceAssemblyReleaseManifest {
        schemaVersion = ReferenceAssemblyContractValues.requireSchema(
                schemaVersion, SCHEMA_VERSION, "Reference Assembly release manifest schemaVersion");
        productLineId = ReferenceAssemblyContractValues.requireProductLine(productLineId);
        releaseAlgorithmId = ReferenceAssemblyContractValues.requireStableId(
                releaseAlgorithmId, "releaseAlgorithmId", 128);
        if (!RELEASE_ALGORITHM_ID.equals(releaseAlgorithmId)) {
            throw new IllegalArgumentException("unsupported Reference Assembly release algorithm");
        }
        Objects.requireNonNull(referenceAssemblyId, "referenceAssemblyId");
        ReferenceAssemblyContractValues.requireExactIdentity(
                referenceAssemblyId.value(), "referenceAssemblyId", 128);
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(consumerContract, "consumerContract");
        Objects.requireNonNull(hostFixture, "hostFixture");
        Objects.requireNonNull(policySnapshot, "policySnapshot");
        component = ReferenceAssemblyContractValues.requireEmbeddedAgentPack(component);
        Objects.requireNonNull(assemblyManifest, "assemblyManifest");
        Objects.requireNonNull(inspectionReport, "inspectionReport");
        Objects.requireNonNull(releaseDecisionPointId, "releaseDecisionPointId");
        ReferenceAssemblyContractValues.requireExactIdentity(
                releaseDecisionPointId.value(), "releaseDecisionPointId", 128);
        Objects.requireNonNull(releaseSubjectHash, "releaseSubjectHash");
        releaseActionRunId = ReferenceAssemblyContractValues.requireExactIdentity(
                releaseActionRunId, "releaseActionRunId", 64);
    }
}
