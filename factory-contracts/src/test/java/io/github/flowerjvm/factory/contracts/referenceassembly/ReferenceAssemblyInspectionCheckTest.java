package io.github.flowerjvm.factory.contracts.referenceassembly;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ReferenceAssemblyInspectionCheckTest {
    @Test
    void stableCodeOutcomeMustMatchPassedFlag() {
        assertDoesNotThrow(() -> new ReferenceAssemblyInspectionCheck(
                "host-fixture", true, "REFERENCE_ASSEMBLY_HOST_FIXTURE_PASSED"));
        assertDoesNotThrow(() -> new ReferenceAssemblyInspectionCheck(
                "host-fixture", false, "REFERENCE_ASSEMBLY_HOST_FIXTURE_FAILED"));
        assertDoesNotThrow(() -> new ReferenceAssemblyInspectionCheck(
                "host-fixture", false, "REFERENCE_ASSEMBLY_HOST_FIXTURE_REJECTED"));

        assertThrows(IllegalArgumentException.class, () -> new ReferenceAssemblyInspectionCheck(
                "host-fixture", true, "REFERENCE_ASSEMBLY_HOST_FIXTURE_FAILED"));
        assertThrows(IllegalArgumentException.class, () -> new ReferenceAssemblyInspectionCheck(
                "host-fixture", false, "REFERENCE_ASSEMBLY_HOST_FIXTURE_PASSED"));
    }
}
