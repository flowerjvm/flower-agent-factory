package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.decision.ActionBackedDecisionRecordLauncher;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionResult;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ActionRequestChannel;
import io.github.flowerjvm.flower.action.runtime.ActionProposerType;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Host seam only; fake Action success here is not human approval or certification evidence. */
class FactoryLocalDecisionRunnerTest {
    private static final String SID = "S-1-5-21-100-200-300-1001";
    @TempDir Path directory;

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void windowsTokenIdentityIsAvailableWithoutReadingEnvironmentOrRecordingADecision() {
        assertTrue(FactoryLocalDecisionOperator.currentWindowsSid().matches("S-1-5-21-[0-9]+-[0-9]+-[0-9]+-[0-9]+"));
    }

    @Test void exactCommandUsesNativeIdentityAndSeparateBindingWithoutSynthesizingAnApproval() throws Exception {
        var proposals = new ArrayList<ActionProposal>(); var contexts = new ArrayList<ExecutionContext>();
        var launcher = new ActionBackedDecisionRecordLauncher((proposal, context) -> {
            proposals.add(proposal); contexts.add(context); return ActionExecutionResult.succeeded(Map.of());
        }, Clock.systemUTC());
        var request = request();
        @SuppressWarnings("unchecked") var decision = (Map<String, Object>) request.get("decision");
        decision.put("outcome", "REJECT");
        var runner = runner(launcher, binding(), request, SID);
        assertTrue(proposals.isEmpty());
        runner.run(new DefaultApplicationArguments("--principal=attacker"));
        assertEquals(1, proposals.size());
        assertEquals("factory.decision.record", proposals.getFirst().actionId());
        assertEquals(ActionRequestChannel.CLI, proposals.getFirst().requestChannel());
        assertEquals(ActionProposerType.USER, proposals.getFirst().proposerType());
        assertEquals(io.github.flowerjvm.factory.application.decision.DecisionRecordInput.from(decision).toMap(), proposals.getFirst().input());
        assertEquals("windows-sid:" + SID, contexts.getFirst().userId());
        assertEquals("local-tenant", contexts.getFirst().tenantId());
        assertEquals("local-project", contexts.getFirst().metadata().get("resource.projectId"));
        assertEquals("point-1", contexts.getFirst().metadata().get("resource.id"));
        assertEquals(Set.of("factory.decision.record", "factory.agent.release.approve"), contexts.getFirst().metadata().get("actor.permissions"));
        assertEquals("local-operator-binding-sha256:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve("binding.json")))),
                contexts.getFirst().metadata().get("actor.authoritySnapshotRef"));
    }

    @Test void wrongNativeSidAndPayloadIdentityCannotReachActionRuntime() throws Exception {
        var launcher = noEffects();
        assertThrows(IllegalStateException.class, () -> runner(launcher, binding(), request(), "S-1-5-21-100-200-300-1002").run(new DefaultApplicationArguments()));
        for (String key : List.of("principal", "tenantId", "permissions", "authoritySnapshotRef", "decidedAt", "projectId")) {
            var request = request();
            @SuppressWarnings("unchecked") var decision = (Map<String, Object>) request.get("decision");
            decision.put(key, "untrusted");
            assertThrows(IllegalArgumentException.class, () -> runner(launcher, binding(), request, SID).run(new DefaultApplicationArguments()), key);
        }
    }

    @Test void differentPointFromTrustedBindingCannotReachRuntime() throws Exception {
        var request = request();
        @SuppressWarnings("unchecked") var decision = (Map<String, Object>) request.get("decision");
        decision.put("decisionPointId", "point-2");
        assertThrows(IllegalArgumentException.class, () -> runner(noEffects(), binding(), request, SID).run(new DefaultApplicationArguments()));
    }

    @Test void narrowIncidentApprovalGrantPreservesNativeSidTenantProjectAndExactPoint() throws Exception {
        var proposals = new ArrayList<ActionProposal>(); var contexts = new ArrayList<ExecutionContext>();
        var launcher = new ActionBackedDecisionRecordLauncher((proposal, context) -> {
            proposals.add(proposal); contexts.add(context); return ActionExecutionResult.succeeded(Map.of());
        }, Clock.systemUTC());
        var binding = binding();
        binding.put("permissions", List.of("factory.decision.record", "factory.incident-application.release.approve"));
        runner(launcher, binding, request(), SID).run(new DefaultApplicationArguments("--principal=other", "--tenantId=other"));
        assertEquals(1, proposals.size());
        var context = contexts.getFirst();
        assertEquals("windows-sid:" + SID, context.userId());
        assertEquals("local-tenant", context.tenantId());
        assertEquals("local-project", context.metadata().get("resource.projectId"));
        assertEquals("point-1", context.metadata().get("resource.id"));
        assertEquals(Set.of("factory.decision.record", "factory.incident-application.release.approve"), context.metadata().get("actor.permissions"));
        assertThrows(IllegalStateException.class, () -> runner(noEffects(), binding, request(), "S-1-5-21-100-200-300-1002")
                .run(new DefaultApplicationArguments()));
        var wrongPoint = request();
        @SuppressWarnings("unchecked") var decision = (Map<String, Object>) wrongPoint.get("decision");
        decision.put("decisionPointId", "other-point");
        assertThrows(IllegalArgumentException.class, () -> runner(noEffects(), binding, wrongPoint, SID).run(new DefaultApplicationArguments()));
    }

    @Test void incidentApprovalBindingRejectsUnknownWildcardAndRenewalGrants() throws Exception {
        for (String permission : List.of("factory.incident-application.*", "factory.incident-application.admin",
                "factory.incident-application.review.renew", "factory.*")) {
            var binding = binding();
            binding.put("permissions", List.of("factory.decision.record", "factory.incident-application.release.approve", permission));
            assertThrows(IllegalArgumentException.class, () -> FactoryLocalDecisionOperator.authenticate(
                    new ObjectMapper().writeValueAsBytes(binding), () -> SID), permission);
        }
    }

    @Test void failedActionIsReportedWithoutDirectDecisionWriteOrAutomaticRetry() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var launcher = new ActionBackedDecisionRecordLauncher((proposal, context) -> {
            calls.incrementAndGet(); return ActionExecutionResult.denied("DENIED", "private details not relayed");
        }, Clock.systemUTC());
        var failure = assertThrows(IllegalStateException.class, () -> runner(launcher, binding(), request(), SID).run(new DefaultApplicationArguments()));
        assertFalse(failure.getMessage().contains("private")); assertEquals(1, calls.get());
    }

    @Test void bindingRejectsUnknownSchemaExtraGrantDuplicateGrantAndSelfAssertedPrincipal() throws Exception {
        for (String mutation : List.of("schema", "extraGrant", "duplicateGrant", "principal")) {
            var binding = binding();
            switch (mutation) {
                case "schema" -> binding.put("schemaVersion", "unrecognized");
                case "extraGrant" -> binding.put("permissions", List.of("factory.decision.record", "admin"));
                case "duplicateGrant" -> binding.put("permissions", List.of("factory.decision.record", "factory.decision.record"));
                default -> binding.put("principal", "admin");
            }
            assertThrows(IllegalArgumentException.class, () -> FactoryLocalDecisionOperator.authenticate(new ObjectMapper().writeValueAsBytes(binding), () -> SID), mutation);
        }
    }

    @Test void strictDocumentsRejectDuplicateFieldsTrailingJsonOversizeAndDirectories() throws Exception {
        for (String json : List.of("{\"x\":1,\"x\":2}", "{\"x\":1} {}", "null", "{")) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> FactoryLocalDecisionDocuments.decode(json.getBytes(StandardCharsets.UTF_8), Set.of("x")));
            assertEquals("LOCAL_DECISION_DOCUMENT_INVALID", failure.getMessage()); assertNull(failure.getCause());
        }
        Path large = directory.resolve("large.json"); Files.writeString(large, "x".repeat(16385));
        assertThrows(IllegalArgumentException.class, () -> FactoryLocalDecisionDocuments.read(large));
        assertThrows(IllegalArgumentException.class, () -> FactoryLocalDecisionDocuments.read(directory));
        assertThrows(IllegalArgumentException.class, () -> FactoryLocalDecisionDocuments.read(Path.of("relative.json")));
    }

    @Test void redirectedBindingAncestorCannotReachActionRuntime() throws Exception {
        Path actual = Files.createDirectory(directory.resolve("actual"));
        Path link = directory.resolve("redirect");
        Files.write(actual.resolve("binding.json"), new ObjectMapper().writeValueAsBytes(binding()));
        Files.write(directory.resolve("request.json"), new ObjectMapper().writeValueAsBytes(request()));
        if (java.io.File.separatorChar == '\\') {
            Process command = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), actual.toString())
                    .redirectErrorStream(true).start();
            assertTrue(command.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, command.exitValue());
        } else Files.createSymbolicLink(link, actual);
        var runner = new FactoryLocalDecisionRunner(noEffects(), link.resolve("binding.json"), directory.resolve("request.json"), () -> SID);
        assertThrows(IllegalArgumentException.class, () -> runner.run(new DefaultApplicationArguments()));
    }

    @Test void disabledLocalEntryNeedsNoIdentityBindingOrLauncher() {
        for (Map<String, Object> properties : List.of(Map.<String, Object>of(), Map.<String, Object>of("factory.decision.local.enabled", "false"))) {
            try (var context = context(properties)) {
                context.refresh(); assertTrue(context.getBeansOfType(FactoryLocalDecisionRunner.class).isEmpty());
            }
        }
    }

    @Test void explicitLocalEntryRequiresBothPathsAndContextRefreshNeverExecutesDecision() {
        for (String missing : List.of("operator-binding-path", "request-path", "none")) {
            var properties = new LinkedHashMap<String, Object>(); properties.put("factory.decision.local.enabled", "true");
            properties.put("factory.decision.local.operator-binding-path", directory.resolve("binding.json").toString());
            properties.put("factory.decision.local.request-path", directory.resolve("request.json").toString());
            properties.remove("factory.decision.local." + missing);
            try (var context = context(properties)) {
                context.registerBean(ActionBackedDecisionRecordLauncher.class, this::noEffects);
                if (missing.equals("none")) {
                    context.refresh(); assertEquals(1, context.getBeansOfType(FactoryLocalDecisionRunner.class).size());
                } else assertThrows(RuntimeException.class, context::refresh);
            }
        }
    }

    private FactoryLocalDecisionRunner runner(ActionBackedDecisionRecordLauncher launcher, Map<String, Object> binding,
            Map<String, Object> request, String sid) throws Exception {
        Path bindingPath = directory.resolve("binding.json"); Path requestPath = directory.resolve("request.json");
        Files.write(bindingPath, new ObjectMapper().writeValueAsBytes(binding));
        Files.write(requestPath, new ObjectMapper().writeValueAsBytes(request));
        return new FactoryLocalDecisionRunner(launcher, bindingPath, requestPath, () -> sid);
    }
    private ActionBackedDecisionRecordLauncher noEffects() {
        return new ActionBackedDecisionRecordLauncher((proposal, context) -> { throw new AssertionError("unexpected Action"); }, Clock.systemUTC());
    }
    private static Map<String, Object> binding() {
        return new LinkedHashMap<>(Map.of("schemaVersion", "factory.local-decision-operator.v1", "windowsSid", SID,
                "tenantId", "local-tenant", "projectId", "local-project", "decisionPointId", "point-1",
                "permissions", List.of("factory.decision.record", "factory.agent.release.approve")));
    }
    private static Map<String, Object> request() {
        return new LinkedHashMap<>(Map.of("schemaVersion", "factory.local-decision-request.v1", "requestKey", "human-command-1",
                "decision", new LinkedHashMap<>(Map.of("decisionPointId", "point-1", "expectedDecisionPointVersion", 0,
                        "subjectHash", "a".repeat(64), "outcome", "APPROVE", "reason", ""))));
    }
    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("local-decision-test", properties));
        context.register(FactoryLocalDecisionConfiguration.class); return context;
    }
}
