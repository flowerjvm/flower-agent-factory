package io.github.flowerjvm.factory.application.production;

import static io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.ids.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentPackProductionIntakeServiceTest {
    @Test
    void createsPristineMicrosecondSessionWithOnlyPlanSelectedRequirementsAndWorkerBindings() {
        var f = new AgentPackProductionIntakeTestFixture(); var input = input("precision");
        var session = f.intake().accept(TENANT, AgentPackProductionIntakeAction.REQUESTER_ID, "precision-key", input);
        var plan = bundle(TENANT, input.buildSessionId()).plan();
        assertEquals(NOW.truncatedTo(ChronoUnit.MICROS), session.createdAt());
        assertEquals(session.createdAt(), session.updatedAt()); assertEquals(session.createdAt(), session.startedAt());
        assertEquals(input.deadlineAt(), session.deadlineAt()); assertEquals(input.maxRepairRounds(), session.maxRepairRounds());
        assertEquals(plan.requirements().reference(), session.requirementsArtifactRef());
        assertEquals(plan.requirements().hash(), session.requirementsHash());
        assertEquals(plan.manager().bindingId(), session.selectedManagerWorkerBinding().orElseThrow());
        assertEquals(plan.coding().bindingId(), session.selectedCodingWorkerBinding().orElseThrow());
        assertEquals("precision-key", session.requestIdempotencyKey());
        assertEquals(AgentPackProductionIntakeAction.REQUESTER_ID, session.createdBy());
        assertEquals(ProductLineId.AGENT_PACK, session.productLineId());
        assertEquals(1, f.commits.get()); assertFalse(f.storedArtifacts.isEmpty());
    }

    @Test
    void restartRetryUsesDurableAcceptanceIdentityAndNeverResetsProgressOrOriginalTimestamps() {
        var f = new AgentPackProductionIntakeTestFixture(); var input = input("restart");
        var first = f.intake().accept(TENANT, AgentPackProductionIntakeAction.REQUESTER_ID, "restart-key", input);
        var progressed = first.requestCancellation(first.updatedAt().plusSeconds(1));
        assertTrue(f.sessions.compareAndSet(first, progressed));
        f.clock.now = NOW.plusSeconds(2);
        // New service instance, no completed Action cache: transaction returns its already accepted, progressed row.
        var replay = f.intake().accept(TENANT, AgentPackProductionIntakeAction.REQUESTER_ID, "restart-key", input);
        assertEquals(progressed, replay); assertEquals(BuildSessionStatus.CANCELLING, replay.status());
        assertEquals(first.createdAt(), replay.createdAt()); assertEquals(first.startedAt(), replay.startedAt());
        assertEquals(1, f.commits.get()); assertEquals(2, f.acceptanceAttempts.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "session", "project", "product-line", "request-key", "created-by", "deadline", "repair-limit"})
    void existingSessionRequiresEveryImmutableAcceptanceIdentityFieldExactly(String mismatch) {
        var f = new AgentPackProductionIntakeTestFixture(); var input = input("identity");
        var session = f.intake().accept(TENANT, AgentPackProductionIntakeAction.REQUESTER_ID, "identity-key", input);
        var changed = switch (mismatch) {
            case "tenant" -> copy(session, "tenantId", new TenantId("another-tenant"));
            case "session" -> copy(session, "buildSessionId", new BuildSessionId("another-session"));
            case "project" -> copy(session, "projectId", new ProjectId("another-project"));
            case "product-line" -> copy(session, "productLineId", ProductLineId.REFERENCE_ASSEMBLY);
            case "request-key" -> copy(session, "requestIdempotencyKey", "another-key");
            case "created-by" -> copy(session, "createdBy", "another-owner");
            case "deadline" -> copy(session, "deadlineAt", session.deadlineAt().plusSeconds(1));
            case "repair-limit" -> copy(session, "maxRepairRounds", 3);
            default -> throw new AssertionError(mismatch);
        };
        assertThrows(IllegalArgumentException.class, () -> AgentPackProductionIntakeAuthority.requireExact(changed,
                TENANT, AgentPackProductionIntakeAction.REQUESTER_ID, "identity-key", input));
        assertEquals(1, f.commits.get());
    }

    @Test
    void deadlineCrossingDuringBoundedReadDoesNotCreateSessionOrStoreAnyArtifact() {
        var f = new AgentPackProductionIntakeTestFixture(); var input = input("deadline");
        f.duringInputs = () -> f.clock.now = input.deadlineAt();
        assertThrows(IllegalArgumentException.class, () -> f.intake().accept(TENANT,
                AgentPackProductionIntakeAction.REQUESTER_ID, "deadline-key", input));
        assertEquals(1, f.inputReads.get()); assertEquals(0, f.acceptanceAttempts.get());
        assertEquals(0, f.commits.get()); assertTrue(f.storedArtifacts.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "session"})
    void inputAssemblerCannotSubstituteAnotherPlanOwner(String mismatch) {
        var f = new AgentPackProductionIntakeTestFixture(); var input = input("owner");
        f.alternateBundle = bundle("tenant".equals(mismatch) ? new TenantId("other") : TENANT,
                "session".equals(mismatch) ? new BuildSessionId("other") : input.buildSessionId());
        assertThrows(IllegalArgumentException.class, () -> f.intake().accept(TENANT,
                AgentPackProductionIntakeAction.REQUESTER_ID, "owner-key", input));
        assertEquals(0, f.acceptanceAttempts.get()); assertTrue(f.storedArtifacts.isEmpty());
    }

    @Test
    void invalidCallerAndLogicalKeyAreRejectedBeforeReadingLocalInputs() {
        var f = new AgentPackProductionIntakeTestFixture(); var input = input("invalid-caller");
        assertThrows(IllegalArgumentException.class, () -> f.intake().accept(TENANT, "human", "key", input));
        assertThrows(IllegalArgumentException.class, () -> f.intake().accept(TENANT, AgentPackProductionIntakeAction.REQUESTER_ID, "key\nsecret", input));
        assertThrows(IllegalArgumentException.class, () -> f.intake().accept(TENANT, AgentPackProductionIntakeAction.REQUESTER_ID, "x".repeat(256), input));
        assertEquals(0, f.inputReads.get()); assertEquals(0, f.commits.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"session-high", "session-low", "project-high", "project-low", "tenant-high", "tenant-low", "key-high", "key-low"})
    void unpairedSurrogateAuthorityIdentifiersAreRejectedBeforeAnyHashRuntimeOrLocalRead(String malformed) {
        var f = new AgentPackProductionIntakeTestFixture(); var valid = input("unicode");
        String bad = "identifier-" + (malformed.endsWith("high") ? '\uD800' : '\uDC00');
        var runtimeCalls = new java.util.concurrent.atomic.AtomicInteger();
        var launcher = new ActionBackedAgentPackProductionIntakeLauncher((proposal, context) -> {
            runtimeCalls.incrementAndGet(); throw new AssertionError("malformed identifier reached runtime");
        }, f.clock);
        if (malformed.startsWith("session")) {
            assertThrows(IllegalArgumentException.class, () -> new AgentPackProductionIntakeInput(new BuildSessionId(bad),
                    valid.projectId(), valid.deadlineAt(), valid.maxRepairRounds()));
        } else if (malformed.startsWith("project")) {
            assertThrows(IllegalArgumentException.class, () -> new AgentPackProductionIntakeInput(valid.buildSessionId(),
                    new ProjectId(bad), valid.deadlineAt(), valid.maxRepairRounds()));
        } else {
            TenantId tenant = malformed.startsWith("tenant") ? new TenantId(bad) : TENANT;
            String key = malformed.startsWith("key") ? bad : "unicode-key";
            assertThrows(IllegalArgumentException.class, () -> f.intake().accept(tenant,
                    AgentPackProductionIntakeAction.REQUESTER_ID, key, valid));
            assertThrows(IllegalArgumentException.class, () -> launcher.submit(tenant, valid.buildSessionId(), valid.projectId(), key, valid));
        }
        assertEquals(0, runtimeCalls.get()); assertEquals(0, f.inputReads.get()); assertEquals(0, f.commits.get());
    }

    @Test
    void wellFormedSupplementaryUnicodeIdentifiersRemainSupported() {
        var input = new AgentPackProductionIntakeInput(new BuildSessionId("session-\uD83C\uDF38"),
                new ProjectId("project-\uD83C\uDF38"), input("valid").deadlineAt(), 0);
        assertEquals(input, AgentPackProductionIntakeInput.from(input.toMap()));
        assertEquals("key-\uD83C\uDF38", AgentPackProductionIntakeInput.boundedText("key-\uD83C\uDF38", "key"));
    }

    @Test
    void artifactHashMismatchIsRejectedByTheRealProductionAcceptanceServiceBeforeCommit() {
        var f = new AgentPackProductionIntakeTestFixture(); var input = input("bad-artifact");
        var bundle = bundle(TENANT, input.buildSessionId()); var changed = new ArrayList<>(bundle.artifacts());
        Artifact first = changed.getFirst();
        changed.set(0, new Artifact(first.tenantId(), first.reference(), first.contentHash(), first.mediaType(), new byte[]{1, 2, 3}));
        f.alternateBundle = new AgentPackProductionIntakeInputs.Bundle(bundle.plan(), changed);
        assertThrows(IllegalArgumentException.class, () -> f.intake().accept(TENANT, AgentPackProductionIntakeAction.REQUESTER_ID, "bad-artifact-key", input));
        assertEquals(0, f.acceptanceAttempts.get()); assertTrue(f.storedArtifacts.isEmpty());
    }

    @Test
    void bundleCopiesItsListAndRejectsMoreThanTheAcceptanceArtifactsBound() {
        var bundle = bundle(TENANT, new BuildSessionId("bounded")); var mutable = new ArrayList<>(bundle.artifacts());
        var copy = new AgentPackProductionIntakeInputs.Bundle(bundle.plan(), mutable);
        int size = copy.artifacts().size(); mutable.clear(); assertEquals(size, copy.artifacts().size());
        assertThrows(UnsupportedOperationException.class, () -> copy.artifacts().clear());
        assertThrows(IllegalArgumentException.class, () -> new AgentPackProductionIntakeInputs.Bundle(bundle.plan(),
                java.util.Collections.nCopies(63, bundle.artifacts().getFirst())));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3})
    void permittedRepairBoundariesAndMaximum48HourDeadlineAreAccepted(int rounds) {
        var f = new AgentPackProductionIntakeTestFixture();
        var input = new AgentPackProductionIntakeInput(new BuildSessionId("bounds-" + rounds), new ProjectId("bounds-project"),
                NOW.truncatedTo(ChronoUnit.MICROS).plusSeconds(48 * 3600), rounds);
        assertEquals(rounds, f.intake().accept(TENANT, AgentPackProductionIntakeAction.REQUESTER_ID, "bounds-key", input).maxRepairRounds());
        assertEquals(input, AgentPackProductionIntakeInput.from(input.toMap())); assertEquals(1, f.commits.get());
    }
}
