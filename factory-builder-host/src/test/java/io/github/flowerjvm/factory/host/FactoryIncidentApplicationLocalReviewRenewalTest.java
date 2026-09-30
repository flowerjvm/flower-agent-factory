package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationArtifacts;
import io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationReviewRenewalAction;
import io.github.flowerjvm.factory.application.flow.FactoryCertificationContinuationRecovery;
import io.github.flowerjvm.factory.application.flow.FactoryReferenceAssemblyFlowRecovery;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.core.engine.Engine;
import io.github.flowerjvm.flower.core.persistence.FlowCheckpointStore;
import io.github.flowerjvm.flower.core.recovery.FlowFactoryRegistry;
import io.github.flowerjvm.flower.core.worker.Worker;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Synthetic host seam only: fake Action success is not renewal, human approval or shipment evidence. */
class FactoryIncidentApplicationLocalReviewRenewalTest {
    private static final String SID = "S-1-5-21-100-200-300-1001";
    private static final String PREFIX = "factory.production.incident-application.local-review-renewal.";
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void exactTwoProductCommandUsesNativeUserAndHashBoundGrantWithoutApprovalOrServiceImpersonation() throws Exception {
        var proposals = new ArrayList<ActionProposal>(); var contexts = new ArrayList<ExecutionContext>();
        ActionRuntime runtime = (proposal, context) -> {
            proposals.add(proposal); contexts.add(context); return ActionExecutionResult.succeeded(Map.of());
        };
        var request = request(input("basic"), input("history"));
        var requestBytes = mapper.writeValueAsBytes(request);
        var binding = binding(requestBytes);
        var entry = entry(runtime, binding, requestBytes, SID);
        assertTrue(proposals.isEmpty());
        entry.execute(); entry.execute();
        assertEquals(2, proposals.size());
        for (int index = 0; index < proposals.size(); index++) {
            var proposal = proposals.get(index); var context = contexts.get(index);
            assertEquals(IncidentApplicationReviewRenewalAction.ID, proposal.actionId());
            assertEquals(ActionRequestChannel.CLI, proposal.requestChannel());
            assertEquals(ActionProposerType.USER, proposal.proposerType());
            assertEquals("windows-sid:" + SID, proposal.requesterId());
            assertEquals(proposal.requesterId(), context.userId());
            assertEquals("tenant-a", context.tenantId());
            assertEquals("project-a", context.metadata().get("resource.projectId"));
            assertEquals(proposal.input().get("buildSessionId"), context.metadata().get("resource.id"));
            assertEquals(Set.of(IncidentApplicationReviewRenewalAction.ID), context.metadata().get("actor.permissions"));
            assertEquals("local-incident-review-renewal-binding-sha256:" + IncidentApplicationArtifacts.hash(mapper.writeValueAsBytes(binding)),
                    context.metadata().get("actor.authoritySnapshotRef"));
            IncidentApplicationReviewRenewalAction.authority(proposal, context);
            assertFalse(proposal.input().containsKey("outcome"));
            assertFalse(proposal.input().containsKey("decidedAt"));
        }
        assertNotEquals(proposals.getFirst().idempotencyKey(), proposals.getLast().idempotencyKey());
    }

    @Test void wrongSidAndEveryChangedExactRequestLockAreRejectedBeforeAnyAction() throws Exception {
        var originalInput = input("basic"); var original = mapper.writeValueAsBytes(request(originalInput));
        var binding = binding(original);
        assertThrows(IllegalStateException.class, () -> entry(noEffects(), binding, original, "S-1-5-21-100-200-300-1002").execute());
        for (String field : List.of("buildSessionId", "projectId", "previousDecisionPointId", "subjectHash", "expectedVersion", "deadlineAt")) {
            var changed = new LinkedHashMap<>(originalInput);
            changed.put(field, field.equals("expectedVersion") ? 5 : field.equals("subjectHash") ? "b".repeat(64)
                    : field.equals("deadlineAt") ? "2026-09-16T01:00:00Z" : "changed");
            var changedBytes = mapper.writeValueAsBytes(request(changed));
            assertThrows(IllegalArgumentException.class, () -> entry(noEffects(), binding, changedBytes, SID).execute(), field);
        }
    }

    @Test void protectedBindingRejectsWildcardExtraPermissionsAndUntrustedIdentityFields() throws Exception {
        var requestBytes = mapper.writeValueAsBytes(request(input("basic")));
        for (String mutation : List.of("schema", "principal", "wildcard", "release", "duplicate", "missing")) {
            var binding = binding(requestBytes);
            switch (mutation) {
                case "schema" -> binding.put("schemaVersion", "factory.local-decision-operator.v1");
                case "principal" -> binding.put("principal", "admin");
                case "wildcard" -> binding.put("permissions", List.of("factory.incident-application.*"));
                case "release" -> binding.put("permissions", List.of(IncidentApplicationReviewRenewalAction.ID, "factory.incident-application.release.approve"));
                case "duplicate" -> binding.put("permissions", List.of(IncidentApplicationReviewRenewalAction.ID, IncidentApplicationReviewRenewalAction.ID));
                default -> binding.remove("requestHash");
            }
            assertThrows(IllegalArgumentException.class, () -> entry(noEffects(), binding, requestBytes, SID).execute(), mutation);
        }
    }

    @Test void wholeBatchPreflightRejectsSecondInvalidEntryDuplicateSessionEmptyAndOverlargeBatch() throws Exception {
        var wrongProject = input("history"); wrongProject.put("projectId", "other-project");
        var spoofed = input("history"); spoofed.put("principal", "admin");
        for (var request : List.of(request(input("basic"), wrongProject), request(input("basic"), spoofed),
                request(input("basic"), input("basic")), request(), request(input("a"), input("b"), input("c")))) {
            var bytes = mapper.writeValueAsBytes(request);
            assertThrows(IllegalArgumentException.class, () -> entry(noEffects(), binding(bytes), bytes, SID).execute());
        }
    }

    @Test void strictBoundedDocumentsRejectDuplicateTrailingJsonOversizeAndRedirectBeforeEffects() throws Exception {
        for (String value : List.of("{\"schemaVersion\":1,\"schemaVersion\":2}", "{} {}", "null")) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> entry(noEffects(), binding(bytes), bytes, SID).execute());
        }
        var bytes = "x".repeat(16385).getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> entry(noEffects(), binding(bytes), bytes, SID).execute());
        assertThrows(IllegalArgumentException.class, () -> new FactoryIncidentApplicationLocalReviewRenewal(noEffects(), directory, directory, () -> SID).execute());
        assertThrows(IllegalArgumentException.class, () -> new FactoryIncidentApplicationLocalReviewRenewal(noEffects(), Path.of("relative.json"), directory, () -> SID).execute());
    }

    @Test void deniedSecondActionAbortsWithoutAutomaticRetryAndExplicitRestartPreservesKeys() throws Exception {
        var bytes = mapper.writeValueAsBytes(request(input("basic"), input("history"))); var binding = binding(bytes);
        var firstKeys = new ArrayList<String>();
        var first = entry((proposal, context) -> {
            firstKeys.add(proposal.idempotencyKey());
            return firstKeys.size() == 1 ? ActionExecutionResult.succeeded(Map.of()) : ActionExecutionResult.denied("DENIED", "private details");
        }, binding, bytes, SID);
        var failure = assertThrows(IllegalStateException.class, first::execute);
        assertEquals(2, firstKeys.size()); assertFalse(failure.getMessage().contains("private"));
        var restartedKeys = new ArrayList<String>();
        entry((proposal, context) -> { restartedKeys.add(proposal.idempotencyKey()); return ActionExecutionResult.succeeded(Map.of()); }, binding, bytes, SID).execute();
        assertEquals(firstKeys, restartedKeys);
    }

    @Test void optInIsDisabledByDefaultAndRequiresLineAndBothExplicitPathsWithoutExecutingOnBeanCreation() {
        for (var properties : List.of(Map.<String,Object>of(), Map.<String,Object>of(PREFIX + "enabled", "false"))) {
            try (var context = context(properties)) {
                context.refresh(); assertTrue(context.getBeansOfType(FactoryIncidentApplicationLocalReviewRenewal.class).isEmpty());
            }
        }
        for (String missing : List.of("line", "operator-binding-path", "request-path", "none")) {
            var properties = new LinkedHashMap<String,Object>();
            properties.put(PREFIX + "enabled", "true"); properties.put("factory.production.incident-application.enabled", "true");
            properties.put(PREFIX + "operator-binding-path", directory.resolve("binding.json").toString());
            properties.put(PREFIX + "request-path", directory.resolve("request.json").toString());
            if (missing.equals("line")) properties.remove("factory.production.incident-application.enabled");
            else properties.remove(PREFIX + missing);
            try (var context = context(properties)) {
                context.registerBean(ActionRuntime.class, this::noEffects);
                if (missing.equals("none")) {
                    context.refresh(); assertEquals(1, context.getBeansOfType(FactoryIncidentApplicationLocalReviewRenewal.class).size());
                } else assertThrows(RuntimeException.class, context::refresh);
            }
        }
    }

    @Test void exactRenewalRunsBeforeEngineAttachAndEveryCheckpointRecovery() {
        var entry = mock(FactoryIncidentApplicationLocalReviewRenewal.class);
        var engine = mock(Engine.class); var worker = mock(Worker.class);
        when(engine.worker(FactoryFlowerConfiguration.FACTORY_WORKER)).thenReturn(worker);
        when(worker.name()).thenReturn(FactoryFlowerConfiguration.FACTORY_WORKER);
        var checkpoints = mock(FlowCheckpointStore.class); var registry = mock(FlowFactoryRegistry.class);
        var certification = mock(FactoryCertificationContinuationRecovery.class); var assembly = mock(FactoryReferenceAssemblyFlowRecovery.class);
        var beans = new StaticListableBeanFactory(); beans.addBean("renewal", entry);
        var hook = new FactoryFlowerConfiguration().recoverFactoryFlows(engine, checkpoints, registry, certification, assembly,
                beans.getBeanProvider(FactoryIncidentApplicationLocalReviewRenewal.class));
        verifyNoInteractions(entry, engine, checkpoints, certification, assembly);
        hook.afterSingletonsInstantiated();
        var ordered = inOrder(entry, engine, checkpoints, certification, assembly);
        ordered.verify(entry).execute(); ordered.verify(engine).attach();
        ordered.verify(checkpoints).findActiveByWorker(FactoryFlowerConfiguration.FACTORY_WORKER);
        ordered.verify(certification).recoverBatch(); ordered.verify(assembly).recoverBatch();
    }

    @Test void failedRenewalPreventsEngineAttachCheckpointRecoveryAndAllOtherContinuationRecovery() {
        var entry = mock(FactoryIncidentApplicationLocalReviewRenewal.class); doThrow(new IllegalStateException("denied")).when(entry).execute();
        var engine = mock(Engine.class); var checkpoints = mock(FlowCheckpointStore.class); var registry = mock(FlowFactoryRegistry.class);
        var certification = mock(FactoryCertificationContinuationRecovery.class); var assembly = mock(FactoryReferenceAssemblyFlowRecovery.class);
        var beans = new StaticListableBeanFactory(); beans.addBean("renewal", entry);
        var hook = new FactoryFlowerConfiguration().recoverFactoryFlows(engine, checkpoints, registry, certification, assembly,
                beans.getBeanProvider(FactoryIncidentApplicationLocalReviewRenewal.class));
        assertThrows(IllegalStateException.class, hook::afterSingletonsInstantiated);
        verifyNoInteractions(engine, checkpoints, registry, certification, assembly);
    }

    private FactoryIncidentApplicationLocalReviewRenewal entry(ActionRuntime runtime, Map<String,Object> binding, byte[] request, String sid) throws Exception {
        Path bindingPath = directory.resolve("binding.json"), requestPath = directory.resolve("request.json");
        Files.write(bindingPath, mapper.writeValueAsBytes(binding)); Files.write(requestPath, request);
        return new FactoryIncidentApplicationLocalReviewRenewal(runtime, bindingPath, requestPath, () -> sid);
    }
    private ActionRuntime noEffects() { return (proposal, context) -> { throw new AssertionError("unexpected Action"); }; }
    private static Map<String,Object> binding(byte[] request) {
        return new LinkedHashMap<>(Map.of("schemaVersion", "factory.local-incident-review-renewal-operator.v1", "windowsSid", SID,
                "tenantId", "tenant-a", "projectId", "project-a", "permissions", List.of(IncidentApplicationReviewRenewalAction.ID),
                "requestHash", IncidentApplicationArtifacts.hash(request)));
    }
    private static Map<String,Object> input(String session) {
        return new LinkedHashMap<>(Map.of("buildSessionId", session, "projectId", "project-a", "expectedVersion", 4,
                "previousDecisionPointId", "point-" + session, "subjectHash", "a".repeat(64), "deadlineAt", "2026-09-16T00:00:00Z"));
    }
    @SafeVarargs private static Map<String,Object> request(Map<String,Object>... inputs) {
        return Map.of("schemaVersion", "factory.local-incident-review-renewal-request.v1", "renewals", List.of(inputs));
    }
    private static AnnotationConfigApplicationContext context(Map<String,Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-local-renewal", properties));
        context.register(FactoryIncidentApplicationLocalReviewRenewalConfiguration.class); return context;
    }
}
