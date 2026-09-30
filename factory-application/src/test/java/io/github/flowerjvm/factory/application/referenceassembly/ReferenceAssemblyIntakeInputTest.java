package io.github.flowerjvm.factory.application.referenceassembly;

import static io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyIntakeActionRuntimeTest.*;
import static io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyIntakeTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReferenceAssemblyIntakeInputTest {
    @ParameterizedTest @ValueSource(strings = {"tenantId", "principal", "permissions", "authoritySnapshotRef", "createdAt", "component", "approval", "maxRepairRounds"})
    void payloadCannotDeclareAuthorityOrExpandItsComponentAndProductionScope(String field) {
        Fixture f = new Fixture(); var input = f.domain.input("strict"); var map = new HashMap<>(input.toMap()); map.put(field, "forbidden");
        var result = f.runtime().handle(proposal(input, "key").toBuilder().input(map).build(), context(input, "run", true));
        assertEquals(ActionExecutionStatus.VALIDATION_FAILED, result.status()); assertEquals(0, f.duplicates.reserves.get());
        assertEquals(0, f.domain.fullReads.get()); assertEquals(0, f.domain.commits.get());
    }

    @ParameterizedTest @ValueSource(strings = {"legacy", "wrong-entry", "nanoseconds", "noncanonical-time", "uppercase-hash", "wrong-type", "unpaired-session", "unpaired-project", "unpaired-certification", "unpaired-reference", "oversized-id", "control", "missing"})
    void strictInputRejectsAlternateEntryMalformedUnicodeAndNoncanonicalWireValues(String fault) {
        var input = new ReferenceAssemblyIntakeTestFixture().input("wire"); var map = new HashMap<>(input.toMap());
        switch (fault) {
            case "legacy" -> map.put("catalogEntryId", "LEGACY_PR4");
            case "wrong-entry" -> map.put("catalogEntryId", "maintenance");
            case "nanoseconds" -> map.put("deadlineAt", input.deadlineAt().plusNanos(1).toString());
            case "noncanonical-time" -> map.put("deadlineAt", "2026-09-07T09:10:00.123456+09:00");
            case "uppercase-hash" -> map.put("sourceHash", "A".repeat(64));
            case "wrong-type" -> map.put("certificationId", 2);
            case "unpaired-session" -> map.put("buildSessionId", "session-\uD800");
            case "unpaired-project" -> map.put("projectId", "project-\uD801");
            case "unpaired-certification" -> map.put("certificationId", "cert-\uDC00");
            case "unpaired-reference" -> map.put("certificationManifestRef", "ref-\uD800");
            case "oversized-id" -> map.put("buildSessionId", "x".repeat(129));
            case "control" -> map.put("projectId", "project\n");
            case "missing" -> map.remove("sourceHash");
            default -> throw new AssertionError();
        }
        assertThrows(RuntimeException.class, () -> ReferenceAssemblyIntakeInput.from(map));
    }

    @Test void typedRoundtripIsImmutableAndDeadlineHasExplicitFuture48HourBound() {
        var input = new ReferenceAssemblyIntakeTestFixture().input("roundtrip");
        assertEquals(input, ReferenceAssemblyIntakeInput.from(input.toMap())); assertEquals(8, input.toMap().size());
        assertThrows(UnsupportedOperationException.class, () -> input.toMap().put("tenantId", "injected"));
        assertDoesNotThrow(() -> input.requireLiveAt(NOW));
        assertThrows(IllegalArgumentException.class, () -> input.requireLiveAt(input.deadlineAt()));
        assertThrows(IllegalArgumentException.class, () -> input.requireLiveAt(input.deadlineAt().minusSeconds(48 * 3600 + 1)));
    }

    @Test void malformedTrustedIdentityCannotCollideThroughUtf8ReplacementBeforeHashing() {
        Fixture f = new Fixture(); var input = f.domain.input("identity");
        var launcher = new ActionBackedReferenceAssemblyIntakeLauncher((p, c) -> { throw new AssertionError("must reject before runtime"); }, f.domain.clock);
        assertThrows(IllegalArgumentException.class, () -> launcher.submit(new TenantId("tenant-\uD800"), input.projectId(), input.buildSessionId(), "key", input));
        assertThrows(IllegalArgumentException.class, () -> launcher.submit(TENANT, input.projectId(), input.buildSessionId(), "key-\uDC00", input));
    }
}
