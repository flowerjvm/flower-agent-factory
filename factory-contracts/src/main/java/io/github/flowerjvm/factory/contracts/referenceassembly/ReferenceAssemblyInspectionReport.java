package io.github.flowerjvm.factory.contracts.referenceassembly;

import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import java.util.List;
import java.util.Objects;

/** Canonical evidence from an independent re-read of the complete Reference Assembly graph. */
public record ReferenceAssemblyInspectionReport(
        String schemaVersion,
        ProductLineId productLineId,
        CertificationArtifactLock assemblyManifest,
        CertificationArtifactLock consumerContract,
        CertificationArtifactLock hostFixture,
        CertificationArtifactLock policySnapshot,
        CertifiedAgentComponentRef component,
        List<ReferenceAssemblyInspectionCheck> checks,
        String status) {

    public static final String SCHEMA_VERSION = "factory.reference-assembly-inspection-report.v1";
    public static final String PASSED = "PASSED";
    public static final String REJECTED = "REJECTED";
    public static final List<String> REQUIRED_CHECK_IDS = List.of(
            "api-signature",
            "component-certification",
            "component-role",
            "consumer-contract",
            "host-fixture",
            "manifest-locks",
            "runtime-version");

    public ReferenceAssemblyInspectionReport {
        schemaVersion = ReferenceAssemblyContractValues.requireSchema(
                schemaVersion, SCHEMA_VERSION, "Reference Assembly inspection report schemaVersion");
        productLineId = ReferenceAssemblyContractValues.requireProductLine(productLineId);
        Objects.requireNonNull(assemblyManifest, "assemblyManifest");
        Objects.requireNonNull(consumerContract, "consumerContract");
        Objects.requireNonNull(hostFixture, "hostFixture");
        Objects.requireNonNull(policySnapshot, "policySnapshot");
        component = ReferenceAssemblyContractValues.requireEmbeddedAgentPack(component);
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        if (!checks.stream().map(ReferenceAssemblyInspectionCheck::checkId).toList()
                .equals(REQUIRED_CHECK_IDS)) {
            throw new IllegalArgumentException(
                    "Reference Assembly inspection checks must contain the exact ordered v1 matrix");
        }
        boolean allPassed = checks.stream().allMatch(ReferenceAssemblyInspectionCheck::passed);
        if ((allPassed && !PASSED.equals(status)) || (!allPassed && !REJECTED.equals(status))) {
            throw new IllegalArgumentException(
                    "Reference Assembly inspection status must match its exact checks");
        }
    }
}
