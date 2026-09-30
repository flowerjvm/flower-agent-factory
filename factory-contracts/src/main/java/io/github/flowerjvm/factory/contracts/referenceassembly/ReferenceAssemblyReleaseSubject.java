package io.github.flowerjvm.factory.contracts.referenceassembly;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import java.util.Objects;

/** Canonical, tenant-free release-review subject for one inspected Reference Assembly snapshot. */
public record ReferenceAssemblyReleaseSubject(
        String schemaVersion,
        ProductLineId productLineId,
        ReferenceAssemblyId referenceAssemblyId,
        long inspectedAssemblyVersion,
        CertificationArtifactLock assemblyManifest,
        CertificationArtifactLock inspectionReport,
        CertificationId componentCertificationId,
        ContentHash componentCandidateHash,
        CertificationArtifactLock componentCertificationManifest,
        CertificationArtifactLock policySnapshot) {

    public static final String SCHEMA_VERSION =
            "factory.reference-assembly-release-subject.v1";

    public ReferenceAssemblyReleaseSubject {
        schemaVersion = ReferenceAssemblyContractValues.requireSchema(
                schemaVersion, SCHEMA_VERSION, "Reference Assembly release subject schemaVersion");
        productLineId = ReferenceAssemblyContractValues.requireProductLine(productLineId);
        Objects.requireNonNull(referenceAssemblyId, "referenceAssemblyId");
        ReferenceAssemblyContractValues.requireExactIdentity(
                referenceAssemblyId.value(), "referenceAssemblyId", 128);
        if (inspectedAssemblyVersion < 0) {
            throw new IllegalArgumentException("inspectedAssemblyVersion must not be negative");
        }
        Objects.requireNonNull(assemblyManifest, "assemblyManifest");
        Objects.requireNonNull(inspectionReport, "inspectionReport");
        Objects.requireNonNull(componentCertificationId, "componentCertificationId");
        ReferenceAssemblyContractValues.requireExactIdentity(
                componentCertificationId.value(), "componentCertificationId", 128);
        Objects.requireNonNull(componentCandidateHash, "componentCandidateHash");
        Objects.requireNonNull(componentCertificationManifest, "componentCertificationManifest");
        Objects.requireNonNull(policySnapshot, "policySnapshot");
    }
}
