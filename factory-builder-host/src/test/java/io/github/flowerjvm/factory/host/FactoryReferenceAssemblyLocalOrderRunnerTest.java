package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineFlowLauncher;
import io.github.flowerjvm.factory.application.referenceassembly.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Transport-only fixtures; a mocked canonical readback is not real certified-component evidence. */
class FactoryReferenceAssemblyLocalOrderRunnerTest {
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
    private static final TenantId TENANT = new TenantId("assembly-local-tenant");
    private static final String KEY = "assembly-local-request";
    private static final String PREFIX = "factory.production.reference-assembly.local-order.";
    private static final ReferenceAssemblyIntakeInput INPUT = new ReferenceAssemblyIntakeInput(
            new BuildSessionId("assembly-local-session"), new ProjectId("assembly-local-project"), NOW.plusSeconds(3600),
            ReferenceAssemblyProductLineCatalog.Entry.MAINTENANCE_INVESTIGATION_V1, new CertificationId("certified-pack"),
            new ContentHash("a".repeat(64)), new CertificationArtifactLock(new ArtifactReference("artifact:certification-manifest"), new ContentHash("b".repeat(64))));

    @Test void explicitSuccessUsesRegisteredProposalAndCanonicalReadbackBeforeInitialFlow() {
        var f = new Fixture();
        when(f.service.requireAcceptedSession(TENANT, INPUT.projectId(), INPUT.buildSessionId(), KEY, INPUT)).thenReturn(session(BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER));
        f.runner().run(new DefaultApplicationArguments());
        assertEquals(1, f.proposals.size());
        assertEquals("factory.reference-assembly.order.submit", f.proposals.getFirst().actionId());
        assertEquals(ActionRequestChannel.INTERNAL, f.proposals.getFirst().requestChannel());
        assertEquals(ActionProposerType.SERVICE, f.proposals.getFirst().proposerType());
        assertEquals(INPUT.toMap(), f.proposals.getFirst().input());
        verify(f.flows).launch(eq(TENANT), eq(INPUT.buildSessionId()), matches("reference-assembly-production-[a-f0-9]{64}"), matches("assembly-production-[a-f0-9]{64}"));
    }

    @Test void deniedOrUncertainIntakeDoesNotReadOrLaunchEvenIfAnOldSessionCouldExist() {
        for (var outcome : List.of(ActionExecutionResult.denied("DENIED", "denied"), ActionExecutionResult.accepted("WAIT", Map.of()),
                ActionExecutionResult.manualReviewFailure("UNCERTAIN", "uncertain"))) {
            var f = new Fixture(); f.result = outcome;
            assertThrows(IllegalStateException.class, () -> f.runner().run(new DefaultApplicationArguments()));
            verifyNoInteractions(f.service, f.flows);
        }
    }

    @Test void exactReceiptReadbackFailureCannotLaunchFlow() {
        var f = new Fixture();
        when(f.service.requireAcceptedSession(TENANT, INPUT.projectId(), INPUT.buildSessionId(), KEY, INPUT)).thenThrow(new IllegalArgumentException("receipt mismatch"));
        assertThrows(IllegalArgumentException.class, () -> f.runner().run(new DefaultApplicationArguments()));
        verifyNoInteractions(f.flows);
    }

    @Test void laterPhaseClosedAndAttentionSessionsStayWithDurableRecovery() {
        for (var status : List.of(BuildSessionStatus.RUNNING, BuildSessionStatus.WAITING_RELEASE_REVIEW, BuildSessionStatus.BLOCKED,
                BuildSessionStatus.MANUAL_REVIEW, BuildSessionStatus.SUCCEEDED, BuildSessionStatus.CANCELLED)) {
            var f = new Fixture();
            when(f.service.requireAcceptedSession(TENANT, INPUT.projectId(), INPUT.buildSessionId(), KEY, INPUT)).thenReturn(session(status, BuildSessionPhase.HUMAN_RELEASE_REVIEW));
            f.runner().run(new DefaultApplicationArguments()); verifyNoInteractions(f.flows);
        }
        var f = new Fixture();
        when(f.service.requireAcceptedSession(TENANT, INPUT.projectId(), INPUT.buildSessionId(), KEY, INPUT))
                .thenReturn(session(BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER).requestCancellation(NOW));
        f.runner().run(new DefaultApplicationArguments()); verifyNoInteractions(f.flows);
    }

    @Test void repeatedTransportUsesNewActionLifecycleAndStableInitialFlowIdentity() {
        var f = new Fixture();
        when(f.service.requireAcceptedSession(TENANT, INPUT.projectId(), INPUT.buildSessionId(), KEY, INPUT)).thenReturn(session(BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER));
        f.runner().run(new DefaultApplicationArguments()); f.runner().run(new DefaultApplicationArguments());
        assertNotEquals(f.contexts.getFirst().runId(), f.contexts.getLast().runId());
        assertEquals(f.proposals.getFirst().idempotencyKey(), f.proposals.getLast().idempotencyKey());
        var runIds = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.flows, times(2)).launch(eq(TENANT), eq(INPUT.buildSessionId()), runIds.capture(), anyString());
        assertEquals(runIds.getAllValues().getFirst(), runIds.getAllValues().getLast());
    }

    @Test void disabledLocalEntryAndIntakeNeedNoDependencies() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(FactoryReferenceAssemblyLocalOrderConfiguration.class, FactoryReferenceAssemblyIntakeConfiguration.class);
            context.refresh();
            assertTrue(context.getBeansOfType(FactoryReferenceAssemblyLocalOrderRunner.class).isEmpty());
            assertTrue(context.getBeansOfType(ReferenceAssemblyIntakeService.class).isEmpty());
        }
    }

    @Test void enabledEntryRequiresEveryExplicitSelectionAndDoesNotRunOnContextRefresh() {
        for (String missing : List.of("tenant-id", "session-id", "project-id", "request-key", "deadline-at", "catalog-entry-id",
                "certification-id", "source-hash", "certification-manifest-ref", "certification-manifest-hash", "none")) {
            var f = new Fixture(); var properties = properties(); properties.remove(PREFIX + missing);
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("local-assembly-test", properties));
                context.registerBean(ActionBackedReferenceAssemblyIntakeLauncher.class, () -> f.intake);
                context.registerBean(ReferenceAssemblyIntakeService.class, () -> f.service);
                context.registerBean(FactoryProductLineFlowLauncher.class, () -> f.flows);
                context.register(FactoryReferenceAssemblyLocalOrderConfiguration.class);
                if (missing.equals("none")) {
                    context.refresh(); assertEquals(1, context.getBeansOfType(FactoryReferenceAssemblyLocalOrderRunner.class).size());
                } else assertThrows(RuntimeException.class, context::refresh, missing);
                assertTrue(f.proposals.isEmpty()); verifyNoInteractions(f.service, f.flows);
            }
        }
    }

    private static final class Fixture {
        final ReferenceAssemblyIntakeService service = mock(ReferenceAssemblyIntakeService.class);
        final FactoryProductLineFlowLauncher flows = mock(FactoryProductLineFlowLauncher.class);
        final List<ActionProposal> proposals = new ArrayList<>(); final List<ExecutionContext> contexts = new ArrayList<>();
        ActionExecutionResult result = ActionExecutionResult.succeeded(Map.of("untrusted-component", "must-not-be-used"));
        final ActionBackedReferenceAssemblyIntakeLauncher intake = new ActionBackedReferenceAssemblyIntakeLauncher((proposal, context) -> {
            proposals.add(proposal); contexts.add(context); return result;
        }, Clock.fixed(NOW, ZoneOffset.UTC));
        FactoryReferenceAssemblyLocalOrderRunner runner() { return new FactoryReferenceAssemblyLocalOrderRunner(intake, service, flows, TENANT, KEY, INPUT); }
    }
    private static Map<String, Object> properties() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put(PREFIX + "enabled", "true"); properties.put(PREFIX + "tenant-id", TENANT.value());
        properties.put(PREFIX + "session-id", INPUT.buildSessionId().value()); properties.put(PREFIX + "project-id", INPUT.projectId().value());
        properties.put(PREFIX + "request-key", KEY); properties.put(PREFIX + "deadline-at", INPUT.deadlineAt().toString());
        properties.put(PREFIX + "catalog-entry-id", INPUT.catalogEntry().name()); properties.put(PREFIX + "certification-id", INPUT.certificationId().value());
        properties.put(PREFIX + "source-hash", INPUT.sourceHash().sha256()); properties.put(PREFIX + "certification-manifest-ref", INPUT.certificationManifest().reference().value());
        properties.put(PREFIX + "certification-manifest-hash", INPUT.certificationManifest().hash().sha256()); return properties;
    }
    private static BuildSession session(BuildSessionStatus status, BuildSessionPhase phase) {
        return new BuildSession(INPUT.buildSessionId(), TENANT, INPUT.projectId(), ProductLineId.REFERENCE_ASSEMBLY, KEY, "factory-builder",
                status, phase, new ArtifactReference("artifact:assembly-requirements"), new ContentHash("c".repeat(64)),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                0, 0, NOW, INPUT.deadlineAt(), Optional.empty(), status.isTerminal() ? Optional.of("SYNTHETIC_TERMINAL") : Optional.empty(),
                Optional.empty(), 0, NOW, NOW);
    }
}
