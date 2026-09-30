package io.github.flowerjvm.factory.contracts.referenceassembly;

/** One deterministic result in the fixed v1 Reference Assembly inspection matrix. */
public record ReferenceAssemblyInspectionCheck(
        String checkId,
        boolean passed,
        String stableCode) {

    public ReferenceAssemblyInspectionCheck {
        checkId = ReferenceAssemblyContractValues.requireStableId(checkId, "checkId", 128);
        stableCode = ReferenceAssemblyContractValues.requireStableCode(stableCode);
        boolean matchingOutcome = passed
                ? stableCode.endsWith("_PASSED")
                : stableCode.endsWith("_FAILED") || stableCode.endsWith("_REJECTED");
        if (!matchingOutcome) {
            throw new IllegalArgumentException(
                    "stableCode outcome must match the Reference Assembly inspection result");
        }
    }
}
