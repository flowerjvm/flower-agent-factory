package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.decision.AgentPackReleaseReviewService;
import io.github.flowerjvm.factory.application.production.AgentPackProductionPlan;
import io.github.flowerjvm.factory.application.production.AgentPackProductionRepairEvidenceReader;
import io.github.flowerjvm.factory.application.production.AgentPackProductionRepairFindings;
import io.github.flowerjvm.factory.application.production.AgentPackProductionService;
import io.github.flowerjvm.factory.application.production.MaintenanceProductionRecipe;
import io.github.flowerjvm.factory.application.production.MaintenanceRepairDemoScenario;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeInput;
import io.github.flowerjvm.factory.application.production.ProductionPreparationResult;
import io.github.flowerjvm.factory.application.verification.AgentPackGenerationVerificationProfiles;
import io.github.flowerjvm.factory.application.verification.VerificationActionEvidenceOwner;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifactDecoder;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.worker.WorkOrder;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerCompletionPayload;
import io.github.flowerjvm.factory.contracts.worker.CodingWorkerInputManifest;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilityCatalog;
import io.github.flowerjvm.factory.contracts.worker.WorkerRunStatus;
import io.github.flowerjvm.factory.infrastructure.production.JacksonAgentPackProductionCodec;
import io.github.flowerjvm.factory.infrastructure.worker.JacksonWorkerProtocolArtifactDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Real JDBC/codec preparation integration only. Phase CAS and blueprint insertion are explicit
 * test setup, not a successful Worker callback, independent verification or human approval.
 */
class JdbcAgentPackProductionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-06T04:00:00Z");
    private static final TenantId TENANT = new TenantId("production-service-tenant");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void v2PreparationLocksMavenRulesAndRestartDoesNotReplaceItsFirstDraftWorkOrder() throws Exception {
        Fixture f = new Fixture("demo-v2-first-draft", true, true, MaintenanceRepairDemoScenario.RECIPE_ID_V2);
        assertEquals(f.plan, f.codec.readPlan(f.codec.writePlan(f.plan)));
        assertEquals(18, MAPPER.readTree(f.codec.writePlan(f.plan)).size());
        Artifact blueprint = f.blueprint("Synthetic v2 preparation fixture; no real Worker or failed gate", false);
        f.artifacts.store(blueprint);
        BuildSession generation = f.transition(BuildSessionPhase.GENERATE_CANDIDATE, Optional.of(blueprint.reference()));
        var first = f.service(NOW.plusSeconds(3)).prepare(TENANT, generation.buildSessionId(), generation.version());
        WorkOrder order = f.orders.find(TENANT, first.workOrderId().orElseThrow()).orElseThrow();
        assertTrue(f.instruction(order).contains(MaintenanceRepairDemoScenario.ID_V2));
        assertTrue(f.instruction(order).contains("FIRST DRAFT ONLY"));
        assertTrue(f.instruction(order).contains("STRICT FACTORY MAVEN BUILD RECIPE (v2)"));
        assertEquals(MaintenanceRepairDemoScenario.policy(MaintenanceRepairDemoScenario.RECIPE_ID_V2).reference(), order.policySnapshotRef());
        assertEquals(MaintenanceInvestigationProductContract.GATE_PROFILE, f.protocol.readInput(order).gateProfile());
        assertTrue(f.protocol.readInput(order).repairLock().isEmpty());
        assertEquals(first, f.service(NOW.plusSeconds(5)).prepare(TENANT, generation.buildSessionId(), generation.version()));
        assertEquals(order, f.orders.find(TENANT, order.workOrderId()).orElseThrow());
        assertEquals(1, f.count("factory_work_order")); assertEquals(0, f.count("factory_candidate_version"));
        assertEquals(0, f.count("factory_verification_run"));
    }

    @Test
    void changingAcceptedDemoVersionIsRejectedAndCannotRetroactivelyReplaceThePlanOrPolicy() {
        for (String recipeId : List.of(MaintenanceRepairDemoScenario.RECIPE_ID, MaintenanceRepairDemoScenario.RECIPE_ID_V2)) {
            String suffix = recipeId.equals(MaintenanceRepairDemoScenario.RECIPE_ID) ? "demo-version-v1" : "demo-version-v2";
            Fixture f = new Fixture(suffix, true, true, recipeId);
            var a = f.plan; var current = f.current();
            var input = new AgentPackProductionIntakeInput(current.buildSessionId(), current.projectId(), current.deadlineAt(), current.maxRepairRounds());
            String other = recipeId.equals(MaintenanceRepairDemoScenario.RECIPE_ID)
                    ? MaintenanceRepairDemoScenario.RECIPE_ID_V2 : MaintenanceRepairDemoScenario.RECIPE_ID;
            f.service(NOW).requireIntakeRecipe(TENANT, input, recipeId);
            assertThrows(IllegalArgumentException.class, () -> f.service(NOW).requireIntakeRecipe(TENANT, input, other));
            var changed = new AgentPackProductionPlan(a.schemaVersion(), a.tenantId(), a.buildSessionId(), other,
                    a.requirements(), a.skillId(), a.skillVersion(), a.skill(), a.dependencyLock(), a.toolchainLock(),
                    a.productContract(), a.apiSignatureIndex(), a.requirementTestMatrix(), a.gateProfile(),
                    MaintenanceRepairDemoScenario.policy(other), a.workspaceRef(), a.manager(), a.coding());
            assertThrows(RuntimeException.class, () -> f.service(NOW).accept(f.pristine, changed,
                    List.of(MaintenanceRepairDemoScenario.policyArtifact(TENANT, other))));
            assertEquals(f.plan, f.service(NOW).readPlan(f.current()));
            assertTrue(f.artifacts.find(TENANT, MaintenanceRepairDemoScenario.policy(other).reference()).isEmpty());
            assertEquals(1, f.count("factory_build_session")); assertEquals(0, f.count("factory_work_order"));
        }
    }

    @Test
    void bothDemoVersionsRequirePositiveRepairBudgetBeforeAnyInputOrAcceptanceWrite() {
        Fixture f = new Fixture("demo-budget", false, false);
        int before = f.count("factory_artifact");
        var input = new AgentPackProductionIntakeInput(f.pristine.buildSessionId(), f.pristine.projectId(),
                f.pristine.deadlineAt(), 0);
        for (String recipeId : List.of(MaintenanceRepairDemoScenario.RECIPE_ID, MaintenanceRepairDemoScenario.RECIPE_ID_V2)) {
            var rejected = assertThrows(IllegalArgumentException.class,
                    () -> f.service(NOW).requireIntakeRecipe(TENANT, input, recipeId));
            assertEquals("AGENT_PACK_PRODUCTION_DEMO_REPAIR_ROUND_REQUIRED", rejected.getMessage());
        }
        f.service(NOW).requireIntakeRecipe(TENANT, input, MaintenanceProductionRecipe.ID);
        assertEquals(before, f.count("factory_artifact")); assertEquals(0, f.count("factory_build_session"));
        assertEquals(0, f.count("factory_work_order"));
    }

    @Test
    void demoFirstDraftKeepsStrictPlanAndWorkerWireAndRestartPreservesItsDeclaredInstruction() throws Exception {
        Fixture f = new Fixture("demo-first-draft", true, true, true);
        var current = f.current();
        var intake = new AgentPackProductionIntakeInput(current.buildSessionId(), current.projectId(), current.deadlineAt(), current.maxRepairRounds());
        f.service(NOW).requireIntakeRecipe(TENANT, intake, MaintenanceRepairDemoScenario.RECIPE_ID);
        assertThrows(IllegalArgumentException.class, () -> f.service(NOW).requireIntakeRecipe(TENANT, intake, MaintenanceProductionRecipe.ID));
        var decoded = f.codec.readPlan(f.codec.writePlan(f.plan));
        assertEquals(f.plan, decoded);
        assertEquals(18, MAPPER.readTree(f.codec.writePlan(f.plan)).size());
        assertEquals(MaintenanceRepairDemoScenario.policy(), decoded.policySnapshot());
        Artifact blueprint = f.blueprint("Demo test blueprint only, not a model-produced artifact", false);
        f.artifacts.store(blueprint);
        BuildSession generation = f.transition(BuildSessionPhase.GENERATE_CANDIDATE, Optional.of(blueprint.reference()));
        var first = f.service(NOW.plusSeconds(3)).prepare(TENANT, generation.buildSessionId(), generation.version());
        WorkOrder order = f.orders.find(TENANT, first.workOrderId().orElseThrow()).orElseThrow();
        assertTrue(f.instruction(order).contains("FIRST DRAFT ONLY"));
        assertTrue(f.instruction(order).contains(MaintenanceRepairDemoScenario.ID));
        assertEquals(MaintenanceRepairDemoScenario.policy().reference(), order.policySnapshotRef());
        var input = f.protocol.readInput(order);
        assertEquals(MaintenanceInvestigationProductContract.GATE_PROFILE, input.gateProfile());
        assertEquals(MaintenanceInvestigationProductContract.lock().hash(), input.productContractBundleHash());
        assertTrue(input.repairLock().isEmpty());
        assertEquals(1, order.revision());
        assertEquals(first, f.service(NOW.plusSeconds(5)).prepare(TENANT, generation.buildSessionId(), generation.version()));
        assertEquals(order, f.orders.find(TENANT, order.workOrderId()).orElseThrow());
        assertEquals(1, f.count("factory_work_order"));
        assertEquals(0, f.count("factory_verification_run"));
        assertEquals(0, f.count("factory_candidate_version"));
    }

    @Test
    void normalOrderCannotBeRetroactivelyReacceptedAsDemoAndNewPolicyRollsBack() {
        Fixture f = Fixture.create("normal-cannot-be-demo", true);
        var a = f.plan;
        var demo = new AgentPackProductionPlan(a.schemaVersion(), a.tenantId(), a.buildSessionId(),
                MaintenanceRepairDemoScenario.RECIPE_ID, a.requirements(), a.skillId(), a.skillVersion(), a.skill(),
                a.dependencyLock(), a.toolchainLock(), a.productContract(), a.apiSignatureIndex(), a.requirementTestMatrix(),
                a.gateProfile(), MaintenanceRepairDemoScenario.policy(), a.workspaceRef(), a.manager(), a.coding());
        assertThrows(RuntimeException.class, () -> f.service(NOW).accept(f.pristine, demo,
                List.of(MaintenanceRepairDemoScenario.policyArtifact(TENANT))));
        assertEquals(f.plan, f.service(NOW).readPlan(f.current()));
        assertTrue(f.artifacts.find(TENANT, MaintenanceRepairDemoScenario.policy().reference()).isEmpty());
        assertEquals(1, f.count("factory_build_session"));
        assertEquals(0, f.count("factory_work_order"));
    }

    @Test
    void acceptanceRejectsSubMicrosecondPrecisionInEachInitialTimestampBeforeAnyWrite() {
        Fixture f = new Fixture("timestamp-rejection", false, false);
        int originalArtifacts = f.count("factory_artifact");
        List<BuildSession> invalid = List.of(
                withTimes(f.pristine, NOW.minusNanos(1), NOW, NOW, NOW.plusSeconds(600)),
                withTimes(f.pristine, NOW, NOW.plusNanos(1), NOW, NOW.plusSeconds(600)),
                withTimes(f.pristine, NOW, NOW, NOW.plusNanos(1), NOW.plusSeconds(600)),
                withTimes(f.pristine, NOW, NOW, NOW, NOW.plusSeconds(600).plusNanos(1)));
        for (BuildSession requested : invalid) {
            var rejected = assertThrows(IllegalArgumentException.class,
                    () -> f.service(NOW.plusSeconds(1)).accept(requested, f.plan, List.of()));
            assertEquals("AGENT_PACK_PRODUCTION_TIMESTAMP_PRECISION_INVALID", rejected.getMessage());
            assertEquals(0, f.count("factory_build_session"));
            assertEquals(originalArtifacts, f.count("factory_artifact"));
            assertTrue(f.artifacts.find(TENANT, AgentPackProductionService.planReference(
                    TENANT, f.pristine.buildSessionId())).isEmpty());
            assertEquals(0, f.count("factory_work_order"));
            assertEquals(0, f.count("factory_worker_run"));
        }
    }

    @Test
    void acceptancePreservesExactMicrosecondTimestampsAcrossJdbcAndServiceRestart() {
        Fixture f = new Fixture("timestamp-micros", false, false);
        Instant created = NOW.plusNanos(123_456_000);
        BuildSession requested = withTimes(f.pristine, created, created, created, created.plusSeconds(600));
        assertEquals(requested, f.service(NOW.plusSeconds(1)).accept(requested, f.plan, List.of()));
        BuildSession stored = f.current();
        assertEquals(requested, stored);
        int artifactCount = f.count("factory_artifact");
        assertEquals(stored, f.service(NOW.plusSeconds(2)).accept(requested, f.plan, List.of()));
        assertEquals(artifactCount, f.count("factory_artifact"));
        assertEquals(1, f.count("factory_build_session"));
    }

    @Test
    void workWindowRejectsZeroNegativeSubMicrosecondAndOverOneHourDurations() {
        Fixture f = Fixture.create("work-window-rejected", true);
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofNanos(1),
                Duration.ofNanos(999), Duration.ofNanos(1001), Duration.ofHours(1).plusNanos(1000))) {
            var rejected = assertThrows(IllegalArgumentException.class,
                    () -> f.service(NOW.plusSeconds(1), invalid));
            assertEquals("AGENT_PACK_PRODUCTION_WORK_WINDOW_INVALID", rejected.getMessage(), invalid.toString());
        }
        assertEquals(0, f.count("factory_work_order"));
        assertEquals(0, f.count("factory_worker_run"));
    }

    @Test
    void workWindowAcceptsOneMicrosecondAndOneHourAndNeverExtendsTheSessionDeadline() {
        for (Duration window : List.of(Duration.ofNanos(1000), Duration.ofHours(1))) {
            Fixture f = Fixture.create("work-window-" + window.toNanos(), true);
            BuildSession design = f.transition(BuildSessionPhase.DESIGN_AGENT, Optional.empty());
            Instant now = NOW.plusSeconds(2).plusNanos(123_456_789);
            var result = f.service(now, window).prepare(TENANT, design.buildSessionId(), design.version());
            WorkOrder order = f.orders.find(TENANT, result.workOrderId().orElseThrow()).orElseThrow();
            Instant created = now.truncatedTo(ChronoUnit.MICROS);
            Instant bounded = created.plus(window);
            assertEquals(created, order.createdAt());
            assertEquals(bounded.isBefore(design.deadlineAt()) ? bounded : design.deadlineAt(), order.deadlineAt());
            var run = f.runs.find(TENANT, result.workerRunId().orElseThrow()).orElseThrow();
            assertEquals(created, run.createdAt());
            assertEquals(created, run.updatedAt());
            assertEquals(order.deadlineAt(), run.deadlineAt());
        }
    }

    @Test
    void preparationTruncatesClockToMicrosAndRestartReusesTheExactPersistedOrder() {
        Fixture f = Fixture.create("prepare-nanoseconds", true);
        BuildSession design = f.transition(BuildSessionPhase.DESIGN_AGENT, Optional.empty());
        Instant now = NOW.plusSeconds(2).plusNanos(987_654_999);
        var first = f.service(now).prepare(TENANT, design.buildSessionId(), design.version());
        WorkOrder order = f.orders.find(TENANT, first.workOrderId().orElseThrow()).orElseThrow();
        assertEquals(NOW.plusSeconds(2).plusNanos(987_654_000), order.createdAt());
        assertEquals(order.createdAt().plus(Duration.ofMinutes(5)), order.deadlineAt());
        var run = f.runs.find(TENANT, first.workerRunId().orElseThrow()).orElseThrow();
        assertEquals(order.createdAt(), run.createdAt());
        assertEquals(first, f.service(NOW.plusSeconds(20).plusNanos(999_999_999))
                .prepare(TENANT, design.buildSessionId(), design.version()));
        assertEquals(order, f.orders.find(TENANT, order.workOrderId()).orElseThrow());
        assertEquals(run, f.runs.find(TENANT, run.workerRunId()).orElseThrow());
        assertEquals(1, f.count("factory_work_order"));
        assertEquals(1, f.count("factory_worker_run"));
    }

    @Test
    void repairSourceManifestOver256KiBIsRejectedBeforeDecoderAndWithoutNewIntent() {
        Fixture f = Fixture.create("repair-manifest-over", true);
        BuildSession repair = f.syntheticRepairWithManifest(256 * 1024 + 1);
        int artifactCount = f.count("factory_artifact");
        var rejected = assertThrows(IllegalArgumentException.class, () -> f.service(NOW.plusSeconds(4))
                .prepare(TENANT, repair.buildSessionId(), repair.version()));
        assertEquals("AGENT_PACK_PRODUCTION_BASE_SOURCE_MANIFEST_BOUND_EXCEEDED", rejected.getMessage());
        assertEquals(0, f.sourceDecodes.get());
        assertEquals(repair, f.current());
        assertEquals(artifactCount, f.count("factory_artifact"));
        assertEquals(1, f.count("factory_work_order"));
        assertEquals(1, f.count("factory_worker_run"));
    }

    @Test
    void repairSourceManifestAt256KiBPassesDecodingButCannotBypassFindingAuthority() {
        Fixture f = Fixture.create("repair-manifest-exact", true);
        BuildSession repair = f.syntheticRepairWithManifest(256 * 1024);
        int artifactCount = f.count("factory_artifact");
        var unapproved = assertThrows(AssertionError.class, () -> f.service(NOW.plusSeconds(4))
                .prepare(TENANT, repair.buildSessionId(), repair.version()));
        assertEquals("no repair fixture", unapproved.getMessage(), "must reach the separate finding-authority reader");
        assertEquals(1, f.sourceDecodes.get());
        assertEquals(repair, f.current());
        assertEquals(artifactCount, f.count("factory_artifact"));
        assertEquals(1, f.count("factory_work_order"));
        assertEquals(1, f.count("factory_worker_run"));
    }

    @Test
    void publicBlueprintByteLimitAccepts64KiBAndRejectsOneMoreBeforeInstructionStaging() {
        for (int size : List.of(64 * 1024, 64 * 1024 + 1)) {
            Fixture f = Fixture.create("blueprint-byte-bound-" + size, true);
            Artifact blueprint = f.blueprint("합성 설계 경계값", false);
            Artifact padded = artifact("padded-blueprint-" + size, padJson(blueprint.content(), size));
            f.artifacts.store(padded);
            BuildSession generation = f.transition(BuildSessionPhase.GENERATE_CANDIDATE, Optional.of(padded.reference()));
            int artifactCount = f.count("factory_artifact");
            if (size == 64 * 1024) {
                var result = f.service(NOW.plusSeconds(2)).prepare(TENANT, generation.buildSessionId(), generation.version());
                WorkOrder order = f.orders.find(TENANT, result.workOrderId().orElseThrow()).orElseThrow();
                byte[] instruction = f.protocol.exact(TENANT, order.instructionArtifactRef(), order.instructionHash()).content();
                assertTrue(instruction.length <= 128 * 1024);
                assertEquals(List.of("pom.xml", "src"), order.allowedWritePaths());
                assertEquals(1, f.count("factory_work_order"));
            } else {
                var rejected = assertThrows(IllegalArgumentException.class, () -> f.service(NOW.plusSeconds(2))
                        .prepare(TENANT, generation.buildSessionId(), generation.version()));
                assertEquals("MAINTENANCE_BLUEPRINT_INVALID", rejected.getMessage());
                assertEquals(artifactCount, f.count("factory_artifact"));
                assertEquals(0, f.count("factory_work_order"));
                assertEquals(0, f.count("factory_worker_run"));
            }
        }
        // The public recipe already caps instruction at 128 KiB and finding at 64 KiB. The
        // combined 256 KiB defense cannot be directly reached without bypassing those boundaries.
    }

    @Test
    void acceptDesignPrepareAndServiceRestartReuseExactWorkAndOriginalTimestamps() {
        Fixture f = Fixture.create("design-restart", true);
        assertEquals(BuildSessionStatus.RUNNING, f.current().status());
        assertEquals(BuildSessionPhase.UNDERSTAND_CUSTOMER, f.current().currentPhase());
        BuildSession design = f.transition(BuildSessionPhase.DESIGN_AGENT, Optional.empty());
        ProductionPreparationResult first = f.service(NOW.plusSeconds(2)).prepare(TENANT, design.buildSessionId(), design.version());
        WorkOrder original = f.orders.find(TENANT, first.workOrderId().orElseThrow()).orElseThrow();
        var input = f.protocol.readInput(original);
        assertEquals(MaintenanceInvestigationProductContract.GATE_PROFILE, input.gateProfile());
        assertEquals(MaintenanceInvestigationProductContract.lock().hash(), input.productContractBundleHash());
        assertEquals(MaintenanceInvestigationProductContract.apiSignatureIndexLock().hash(), input.apiSignatureIndexHash());
        assertEquals(MaintenanceInvestigationProductContract.requirementTestMatrixLock().hash(), input.requirementTestMatrixHash());
        assertEquals(f.plan.coding().bindingId(), f.runs.find(TENANT, first.workerRunId().orElseThrow()).orElseThrow().workerBindingId());
        assertEquals(WorkerRunStatus.REQUESTED, f.runs.find(TENANT, first.workerRunId().orElseThrow()).orElseThrow().status());
        assertEquals(List.of("blueprint.json"), original.allowedWritePaths());
        assertTrue(f.instruction(original).contains(new String(MaintenanceInvestigationProductContract.requirementsBytes(), StandardCharsets.UTF_8)));

        var restarted = f.service(NOW.plusSeconds(20));
        ProductionPreparationResult retry = restarted.prepare(TENANT, design.buildSessionId(), design.version());
        assertEquals(first, retry);
        assertEquals(original, f.orders.find(TENANT, first.workOrderId().orElseThrow()).orElseThrow());
        assertEquals(NOW.plusSeconds(2), original.createdAt());
        assertEquals(1, f.count("factory_work_order")); assertEquals(1, f.count("factory_worker_run"));
        assertEquals(0, f.count("factory_candidate_version")); assertEquals(0, f.count("factory_verification_run"));
        assertEquals(0, f.count("factory_decision_point"));
    }

    @Test
    void generationUsesActualRequirementsAndValidatedUntrustedBlueprintWithFixedWriteScope() {
        Fixture f = Fixture.create("generation", true);
        BuildSession design = f.transition(BuildSessionPhase.DESIGN_AGENT, Optional.empty());
        f.service(NOW.plusSeconds(2)).prepare(TENANT, design.buildSessionId(), design.version());
        Artifact blueprint = f.blueprint("Fixture evidence normalization pipeline", false);
        f.artifacts.store(blueprint);
        BuildSession generation = f.transition(BuildSessionPhase.GENERATE_CANDIDATE, Optional.of(blueprint.reference()));
        var result = f.service(NOW.plusSeconds(3)).prepare(TENANT, generation.buildSessionId(), generation.version());
        WorkOrder order = f.orders.find(TENANT, result.workOrderId().orElseThrow()).orElseThrow();
        String instruction = f.instruction(order);
        assertTrue(instruction.contains("Fixture evidence normalization pipeline"));
        assertTrue(instruction.contains("UNTRUSTED DESIGN CONTEXT"));
        assertTrue(instruction.contains(new String(MaintenanceInvestigationProductContract.requirementsBytes(), StandardCharsets.UTF_8)));
        assertEquals(List.of("pom.xml", "src"), order.allowedWritePaths());
        assertTrue(order.allowedReadPaths().isEmpty());
        assertFalse(order.allowedWritePaths().contains("blueprint.json"));
        assertEquals(MaintenanceInvestigationProductContract.GATE_PROFILE, f.protocol.readInput(order).gateProfile());
        assertEquals(WorkerRunStatus.REQUESTED, f.runs.find(TENANT, result.workerRunId().orElseThrow()).orElseThrow().status());
        assertEquals(2, f.count("factory_work_order"));
        assertEquals(0, f.count("factory_verification_run")); assertEquals(0, f.count("factory_candidate_version"));
    }

    @Test
    void missingOrWrongImmutableProductionPlanFailsBeforePreparingAnOrder() {
        Fixture missing = Fixture.create("missing-plan", false);
        BuildSession design = missing.transition(BuildSessionPhase.DESIGN_AGENT, Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> missing.service(NOW.plusSeconds(2))
                .prepare(TENANT, design.buildSessionId(), design.version()));
        assertEquals(0, missing.count("factory_work_order"));

        Fixture wrong = Fixture.create("wrong-plan", true);
        BuildSession current = wrong.transition(BuildSessionPhase.DESIGN_AGENT, Optional.empty());
        ArtifactReference ref = AgentPackProductionService.planReference(TENANT, current.buildSessionId());
        byte[] changed = new String(wrong.codec.writePlan(wrong.plan), StandardCharsets.UTF_8)
                .replace(MaintenanceInvestigationProductContract.GATE_PROFILE, "unapproved-profile").getBytes(StandardCharsets.UTF_8);
        wrong.replaceTestArtifact(ref, changed, true);
        assertThrows(IllegalArgumentException.class, () -> wrong.service(NOW.plusSeconds(2))
                .prepare(TENANT, current.buildSessionId(), current.version()));
        assertEquals(0, wrong.count("factory_work_order"));
    }

    @Test
    void mutatedArtifactBytesAreRehashedAndNeverBecomeWorkerAuthority() {
        Fixture f = Fixture.create("mutated-plan", true);
        BuildSession design = f.transition(BuildSessionPhase.DESIGN_AGENT, Optional.empty());
        f.replaceTestArtifact(AgentPackProductionService.planReference(TENANT, design.buildSessionId()),
                "corrupt bytes".getBytes(StandardCharsets.UTF_8), false);
        assertThrows(RuntimeException.class, () -> f.service(NOW.plusSeconds(2))
                .prepare(TENANT, design.buildSessionId(), design.version()));
        assertEquals(0, f.count("factory_work_order")); assertEquals(0, f.count("factory_worker_run"));
    }

    @Test
    void missingAndTraversalBlueprintsCannotBroadenTheGenerationWorkspace() {
        Fixture missing = Fixture.create("missing-blueprint", true);
        BuildSession generation = missing.transition(BuildSessionPhase.GENERATE_CANDIDATE,
                Optional.of(new ArtifactReference("artifact:missing-blueprint")));
        assertThrows(IllegalArgumentException.class, () -> missing.service(NOW.plusSeconds(2))
                .prepare(TENANT, generation.buildSessionId(), generation.version()));

        Fixture invalid = Fixture.create("invalid-blueprint", true);
        Artifact bad = invalid.blueprint("Invalid path proposal", true); invalid.artifacts.store(bad);
        BuildSession current = invalid.transition(BuildSessionPhase.GENERATE_CANDIDATE, Optional.of(bad.reference()));
        assertThrows(IllegalArgumentException.class, () -> invalid.service(NOW.plusSeconds(2))
                .prepare(TENANT, current.buildSessionId(), current.version()));
        assertEquals(0, missing.count("factory_work_order")); assertEquals(0, invalid.count("factory_work_order"));
    }

    @Test
    void replacedBlueprintCannotSilentlyChangeAnExistingGenerationOrder() {
        Fixture f = Fixture.create("blueprint-drift", true);
        Artifact blueprint = f.blueprint("First immutable design", false); f.artifacts.store(blueprint);
        BuildSession generation = f.transition(BuildSessionPhase.GENERATE_CANDIDATE, Optional.of(blueprint.reference()));
        var first = f.service(NOW.plusSeconds(2)).prepare(TENANT, generation.buildSessionId(), generation.version());
        WorkOrder original = f.orders.find(TENANT, first.workOrderId().orElseThrow()).orElseThrow();
        f.replaceTestArtifact(blueprint.reference(), f.blueprint("Changed design after preparation", false).content(), true);
        assertThrows(IllegalArgumentException.class, () -> f.service(NOW.plusSeconds(5))
                .prepare(TENANT, generation.buildSessionId(), generation.version()));
        assertEquals(original, f.orders.find(TENANT, first.workOrderId().orElseThrow()).orElseThrow());
        assertEquals(1, f.count("factory_work_order")); assertEquals(1, f.count("factory_worker_run"));
    }

    @Test
    void wrongTenantVersionOrCancellationCannotPrepareAndCannotReuseAnOldResult() {
        Fixture f = Fixture.create("scope", true);
        BuildSession design = f.transition(BuildSessionPhase.DESIGN_AGENT, Optional.empty());
        var service = f.service(NOW.plusSeconds(2));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(new TenantId("foreign"), design.buildSessionId(), design.version()));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(TENANT, design.buildSessionId(), design.version() + 1));
        service.prepare(TENANT, design.buildSessionId(), design.version());
        BuildSession cancelled = design.requestCancellation(NOW.plusSeconds(3));
        assertTrue(f.sessions.compareAndSet(design, cancelled));
        assertThrows(IllegalArgumentException.class, () -> f.service(NOW.plusSeconds(4))
                .prepare(TENANT, cancelled.buildSessionId(), cancelled.version()));
        assertEquals(1, f.count("factory_work_order")); assertEquals(1, f.count("factory_worker_run"));
        assertEquals(0, f.count("factory_decision_point"));
    }

    private static final class Fixture {
        final DataSource source;
        final JdbcBuildSessionRepository sessions;
        final JdbcWorkOrderRepository orders;
        final JdbcWorkerRunRepository runs;
        final JdbcArtifactStore artifacts;
        final WorkerProtocolArtifacts protocol;
        final AtomicInteger sourceDecodes = new AtomicInteger();
        final JacksonAgentPackProductionCodec codec = new JacksonAgentPackProductionCodec(MAPPER);
        final BuildSession pristine;
        final AgentPackProductionPlan plan;

        private Fixture(String suffix, boolean accept) {
            this(suffix, accept, true);
        }

        private Fixture(String suffix, boolean accept, boolean seedSession) {
            this(suffix, accept, seedSession, false);
        }

        private Fixture(String suffix, boolean accept, boolean seedSession, boolean demo) {
            this(suffix, accept, seedSession, demo ? MaintenanceRepairDemoScenario.RECIPE_ID : MaintenanceProductionRecipe.ID);
        }

        private Fixture(String suffix, boolean accept, boolean seedSession, String recipeId) {
            source = FactoryDatabaseMigrationsTest.h2("production_service_" + suffix);
            FactoryDatabaseMigrations.migrate(source);
            sessions = new JdbcBuildSessionRepository(source); orders = new JdbcWorkOrderRepository(source, MAPPER);
            runs = new JdbcWorkerRunRepository(source, MAPPER); artifacts = new JdbcArtifactStore(source, Clock.fixed(NOW, ZoneOffset.UTC));
            var decoder = new JacksonWorkerProtocolArtifactDecoder(MAPPER);
            protocol = new WorkerProtocolArtifacts(artifacts, new WorkerProtocolArtifactDecoder() {
                public CodingWorkerCompletionPayload decodeCompletionPayload(byte[] content) { return decoder.decodeCompletionPayload(content); }
                public CodingWorkerInputManifest decodeInputManifest(byte[] content) { return decoder.decodeInputManifest(content); }
                public CandidateSourceManifest decodeCandidateSourceManifest(byte[] content) {
                    sourceDecodes.incrementAndGet();
                    return decoder.decodeCandidateSourceManifest(content);
                }
            });
            pristine = new BuildSession(new BuildSessionId("service-" + suffix), TENANT, new ProjectId("project-" + suffix),
                    ProductLineId.AGENT_PACK, "request-" + suffix, "fixture-operator", BuildSessionStatus.RUNNING,
                    BuildSessionPhase.UNDERSTAND_CUSTOMER, MaintenanceInvestigationProductContract.requirementsLock().reference(),
                    MaintenanceInvestigationProductContract.requirementsLock().hash(), Optional.of("manager-role"), Optional.of("coding-worker"),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, 2, NOW, NOW.plusSeconds(600),
                    Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW, NOW);
            Artifact skill = artifact("skill-" + suffix, "test-only immutable skill projection".getBytes(StandardCharsets.UTF_8));
            Artifact dependencies = artifact("dependencies-" + suffix, "test-only pinned dependency lock".getBytes(StandardCharsets.UTF_8));
            Artifact toolchain = artifact("toolchain-" + suffix, "test-only Java21 toolchain lock".getBytes(StandardCharsets.UTF_8));
            Artifact policy = MaintenanceRepairDemoScenario.isDemoRecipe(recipeId) ? MaintenanceRepairDemoScenario.policyArtifact(TENANT, recipeId)
                    : artifact("policy-" + suffix, "test-only bounded production policy".getBytes(StandardCharsets.UTF_8));
            var capabilities = new WorkerCapabilities(Set.of(WorkerCapabilityCatalog.REPOSITORY_READ,
                    WorkerCapabilityCatalog.BOUNDED_PATCH_WRITE, WorkerCapabilityCatalog.FILE_CREATE,
                    WorkerCapabilityCatalog.STRUCTURED_OUTPUT, WorkerCapabilityCatalog.COOPERATIVE_CANCEL,
                    WorkerCapabilityCatalog.SANDBOX_ENFORCEMENT));
            plan = new AgentPackProductionPlan(AgentPackProductionPlan.SCHEMA_VERSION, TENANT, pristine.buildSessionId(),
                    recipeId,
                    MaintenanceInvestigationProductContract.requirementsLock(), "fixture-skill", "1.0.0",
                    lock(skill), lock(dependencies), lock(toolchain), MaintenanceInvestigationProductContract.lock(),
                    MaintenanceInvestigationProductContract.apiSignatureIndexLock(), MaintenanceInvestigationProductContract.requirementTestMatrixLock(),
                    MaintenanceInvestigationProductContract.GATE_PROFILE, lock(policy), "fixture-workspace",
                    new AgentPackProductionPlan.WorkerBinding("manager-role", "fixture-v1", capabilities),
                    new AgentPackProductionPlan.WorkerBinding("coding-worker", "fixture-v1", capabilities));
            var inputs = new ArrayList<>(MaintenanceInvestigationProductContract.artifacts(TENANT));
            inputs.addAll(List.of(skill, dependencies, toolchain, policy));
            if (accept) service(NOW).accept(pristine, plan, inputs);
            else { inputs.forEach(artifacts::store); if (seedSession) sessions.create(pristine); }
        }

        static Fixture create(String suffix, boolean accept) { return new Fixture(suffix, accept); }
        BuildSession current() { return sessions.find(TENANT, pristine.buildSessionId()).orElseThrow(); }
        BuildSession transition(BuildSessionPhase phase, Optional<ArtifactReference> blueprint) {
            BuildSession a = current();
            BuildSession next = new BuildSession(a.buildSessionId(), a.tenantId(), a.projectId(), a.productLineId(),
                    a.requestIdempotencyKey(), a.createdBy(), BuildSessionStatus.RUNNING, phase, a.requirementsArtifactRef(),
                    a.requirementsHash(), a.selectedManagerWorkerBinding(), a.selectedCodingWorkerBinding(), blueprint,
                    a.currentCandidateId(), a.currentCandidateHash(), a.currentCertificationId(), a.repairRound(), a.maxRepairRounds(),
                    a.startedAt(), a.deadlineAt(), a.cancellationRequestedAt(), a.terminalCode(), a.terminalMessage(),
                    a.version() + 1, a.createdAt(), a.updatedAt().plusSeconds(1));
            assertTrue(sessions.compareAndSet(a, next)); return next;
        }
        AgentPackProductionService service(Instant now) {
            return service(now, Duration.ofMinutes(5));
        }
        AgentPackProductionService service(Instant now, Duration workWindow) {
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            var candidates = new JdbcCandidateVersionRepository(source);
            var verifications = new JdbcVerificationRunRepository(source);
            var profiles = new AgentPackGenerationVerificationProfiles(orders, protocol, candidates);
            VerificationActionEvidenceOwner owners = run -> { throw new AssertionError("preparation must not query successful verification ownership"); };
            var reviews = new AgentPackReleaseReviewService(sessions, candidates, verifications, profiles,
                    run -> { throw new AssertionError("no verification fixture is approved by these tests"); }, owners, artifacts,
                    (session, candidate, verification, requested) -> { throw new AssertionError("these tests never open a review"); }, clock, Duration.ofMinutes(5));
            var findings = new AgentPackProductionRepairFindings(sessions, verifications, profiles, owners,
                    new JdbcDecisionPointRepository(source, MAPPER), new JdbcDecisionRepository(source),
                    new AgentPackProductionRepairEvidenceReader() {
                        public ContentHash validateSource(CandidateVersion candidate) { throw new AssertionError("no repair fixture"); }
                        public Failure readFailure(CandidateVersion candidate, VerificationRun run) { throw new AssertionError("no repair fixture"); }
                    }, clock);
            return new AgentPackProductionService(sessions, orders, candidates, artifacts, protocol, codec,
                    new JdbcAgentPackWorkPreparationTransaction(source, MAPPER, clock),
                    reviews, findings, clock, workWindow);
        }
        BuildSession syntheticRepairWithManifest(int manifestBytes) {
            Artifact blueprint = blueprint("Synthetic repair boundary fixture", false);
            artifacts.store(blueprint);
            BuildSession generation = transition(BuildSessionPhase.GENERATE_CANDIDATE, Optional.of(blueprint.reference()));
            var first = service(NOW.plusSeconds(2)).prepare(TENANT, generation.buildSessionId(), generation.version());
            Artifact file = artifact("synthetic-source-file", "test-only fixture text".getBytes(StandardCharsets.UTF_8));
            artifacts.store(file);
            String path = "src/main/resources/synthetic.txt";
            ContentHash tree = hash((path + "\t" + file.contentHash().sha256()).getBytes(StandardCharsets.UTF_8));
            CandidateId candidateId = new CandidateId("synthetic-" + pristine.buildSessionId().value());
            var sourceJson = MAPPER.createObjectNode();
            sourceJson.put("schemaVersion", CandidateSourceManifest.SCHEMA_VERSION);
            sourceJson.put("candidateId", candidateId.value()); sourceJson.put("buildSessionId", generation.buildSessionId().value());
            sourceJson.put("sourceLockAlgorithmId", CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID);
            sourceJson.put("candidateHash", tree.sha256()); sourceJson.put("fileCount", 1); sourceJson.put("totalBytes", file.content().length);
            var entry = sourceJson.putArray("files").addObject();
            entry.put("path", path); entry.put("artifactRef", file.reference().value());
            entry.put("contentHash", file.contentHash().sha256()); entry.put("sizeBytes", file.content().length);
            Artifact manifest = artifact("synthetic-source-manifest-" + manifestBytes,
                    padJson(sourceJson.toString().getBytes(StandardCharsets.UTF_8), manifestBytes));
            artifacts.store(manifest);
            // Explicit test setup only, not a Worker success, accepted repair finding or produced product.
            var candidate = new CandidateVersion(candidateId, TENANT, generation.buildSessionId(), Optional.empty(),
                    manifest.reference(), tree, plan.dependencyLock().reference(), plan.dependencyLock().hash(),
                    plan.toolchainLock().reference(), plan.toolchainLock().hash(), CandidateVersionStatus.GENERATED,
                    first.workOrderId().orElseThrow(), NOW.plusSeconds(2));
            new JdbcCandidateVersionRepository(source).create(candidate);
            BuildSession repair = new BuildSession(generation.buildSessionId(), TENANT, generation.projectId(), ProductLineId.AGENT_PACK,
                    generation.requestIdempotencyKey(), generation.createdBy(), BuildSessionStatus.REPAIRING,
                    BuildSessionPhase.GENERATE_CANDIDATE, generation.requirementsArtifactRef(), generation.requirementsHash(),
                    generation.selectedManagerWorkerBinding(), generation.selectedCodingWorkerBinding(), generation.currentBlueprintRef(),
                    Optional.of(candidateId), Optional.of(tree), Optional.empty(), 1, generation.maxRepairRounds(),
                    generation.startedAt(), generation.deadlineAt(), Optional.empty(), Optional.empty(), Optional.empty(),
                    generation.version() + 1, generation.createdAt(), NOW.plusSeconds(3));
            assertTrue(sessions.compareAndSet(generation, repair));
            sourceDecodes.set(0);
            return repair;
        }
        Artifact blueprint(String summary, boolean traversal) {
            var json = MAPPER.createObjectNode(); json.put("schemaVersion", MaintenanceProductionRecipe.BLUEPRINT_SCHEMA_VERSION);
            json.put("productContractHash", MaintenanceInvestigationProductContract.lock().hash().sha256()); json.put("summary", summary);
            var files = json.putArray("sourceFiles"); files.add("pom.xml"); files.add(MaintenanceProductionRecipe.BRIDGE_SOURCE_PATH);
            files.add(traversal ? "../forbidden.java" : "src/test/java/InvestigationTest.java");
            json.putArray("designNotes").add("Fixture design data, not authority");
            return artifact("blueprint-" + pristine.buildSessionId().value(), json.toString().getBytes(StandardCharsets.UTF_8));
        }
        String instruction(WorkOrder order) { return new String(protocol.exact(TENANT, order.instructionArtifactRef(), order.instructionHash()).content(), StandardCharsets.UTF_8); }
        void replaceTestArtifact(ArtifactReference ref, byte[] content, boolean changeHash) {
            String sql = changeHash
                    ? "UPDATE factory_artifact SET content_base64=?,content_size=?,content_hash=? WHERE tenant_id=? AND artifact_ref=?"
                    : "UPDATE factory_artifact SET content_base64=?,content_size=? WHERE tenant_id=? AND artifact_ref=?";
            try (var c = source.getConnection(); var s = c.prepareStatement(sql)) {
                int i = 1; s.setString(i++, Base64.getEncoder().encodeToString(content)); s.setLong(i++, content.length);
                if (changeHash) s.setString(i++, hash(content).sha256()); s.setString(i++, TENANT.value()); s.setString(i, ref.value());
                assertEquals(1, s.executeUpdate());
            } catch (SQLException failure) { throw new AssertionError(failure); }
        }
        int count(String table) {
            if (!Set.of("factory_work_order", "factory_worker_run", "factory_candidate_version", "factory_verification_run",
                    "factory_decision_point", "factory_build_session", "factory_artifact").contains(table)) throw new IllegalArgumentException("table");
            try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                r.next(); return r.getInt(1);
            } catch (SQLException failure) { throw new AssertionError(failure); }
        }
    }

    private static BuildSession withTimes(BuildSession a, Instant created, Instant updated, Instant started, Instant deadline) {
        return new BuildSession(a.buildSessionId(), a.tenantId(), a.projectId(), a.productLineId(),
                a.requestIdempotencyKey(), a.createdBy(), a.status(), a.currentPhase(), a.requirementsArtifactRef(),
                a.requirementsHash(), a.selectedManagerWorkerBinding(), a.selectedCodingWorkerBinding(), a.currentBlueprintRef(),
                a.currentCandidateId(), a.currentCandidateHash(), a.currentCertificationId(), a.repairRound(), a.maxRepairRounds(),
                started, deadline, a.cancellationRequestedAt(), a.terminalCode(), a.terminalMessage(), a.version(), created, updated);
    }

    private static byte[] padJson(byte[] json, int size) {
        assertTrue(json.length < size);
        byte[] padded = Arrays.copyOf(json, size);
        Arrays.fill(padded, json.length, padded.length, (byte) ' ');
        return padded;
    }

    private static CertificationArtifactLock lock(Artifact artifact) { return new CertificationArtifactLock(artifact.reference(), artifact.contentHash()); }
    private static Artifact artifact(String id, byte[] bytes) { return new Artifact(TENANT, new ArtifactReference("artifact:" + id), hash(bytes), "application/json", bytes); }
    private static ContentHash hash(byte[] bytes) {
        try { return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
}
