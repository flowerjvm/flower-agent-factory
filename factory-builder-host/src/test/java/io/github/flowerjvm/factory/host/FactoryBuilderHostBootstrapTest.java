package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.production.AgentPackProductionPhasePreparation;
import io.github.flowerjvm.factory.application.verification.VerificationEvidenceValidator;
import io.github.flowerjvm.factory.application.verification.VerificationReviewEvidenceRecorder;
import io.github.flowerjvm.factory.infrastructure.verification.ArtifactVerificationEvidenceValidator;
import io.github.flowerjvm.factory.infrastructure.verification.ArtifactVerificationReviewEvidence;
import io.github.flowerjvm.factory.contracts.worker.CodingWorker;
import io.github.flowerjvm.factory.application.flow.FactoryFlowCancellationRecovery;
import io.github.flowerjvm.factory.application.flow.FactoryFlowCancellationService;
import io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationLedger;
import io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationFlowFactory;
import io.github.flowerjvm.flower.core.engine.Engine;
import io.github.flowerjvm.flower.core.engine.EngineState;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Path;
import java.nio.file.Files;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class FactoryBuilderHostBootstrapTest {
    @TempDir Path directory;

    @Test
    void executableIncidentLineRunsFlowerAndCancellationWithoutCodexOrImplicitProduction() throws Exception {
        var properties = new java.util.HashMap<String, Object>();
        properties.put("spring.datasource.url", "jdbc:h2:mem:incident-bootstrap-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        properties.put("spring.datasource.username", "sa"); properties.put("spring.datasource.password", "");
        properties.put("spring.main.banner-mode", "off"); properties.put("factory.worker.codex.enabled", "false");
        properties.put("factory.production.agent-pack.intake-enabled", "false");
        properties.put("factory.production.agent-pack.local-order.enabled", "false");
        properties.put("factory.production.reference-assembly.local-order.enabled", "false");
        properties.put("factory.production.incident-application.enabled", "true");
        properties.put("factory.production.incident-application.local-order.enabled", "false");
        properties.put("factory.production.incident-application.catalog-root", directory.resolve("absent-catalog").toString());
        properties.put("factory.production.incident-application.maven-repository", directory.resolve("absent-repository").toString());
        properties.put("factory.production.incident-application.workspace-root", directory.resolve("absent-workspace").toString());
        properties.put("factory.production.incident-application.docker-executable", "equipment-must-not-run");
        try (var context = new SpringApplicationBuilder(FactoryBuilderHostApplication.class)
                .web(WebApplicationType.NONE).properties(properties).run()) {
            assertEquals(EngineState.RUNNING, context.getBean(Engine.class).state());
            assertEquals(1, context.getBeansOfType(FactoryFlowCancellationService.class).size());
            assertEquals(1, context.getBeansOfType(FactoryFlowCancellationRecovery.class).size());
            assertEquals(1, context.getBeansOfType(IncidentApplicationFlowFactory.class).size());
            assertTrue(context.getBeansOfType(CodingWorker.class).isEmpty());
            assertTrue(context.getBeansOfType(FactoryIncidentApplicationLocalOrderRunner.class).isEmpty());
            assertTrue(context.getBean(IncidentApplicationLedger.class).active(32).isEmpty());
            assertTrue(!Files.exists(directory.resolve("absent-workspace")));
        }
    }

    @Test
    void executableHostWiresManagedDataSourceAndFlowerLifecycleWithProductionDisabled() throws Exception {
        try (var context = new SpringApplicationBuilder(FactoryBuilderHostApplication.class)
                .web(WebApplicationType.NONE).properties(Map.of(
                        "spring.datasource.url", "jdbc:h2:mem:host-bootstrap-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "spring.datasource.username", "sa", "spring.datasource.password", "",
                        "spring.main.banner-mode", "off",
                        "factory.worker.codex.enabled", "false",
                        "factory.production.agent-pack.intake-enabled", "false",
                        "factory.production.agent-pack.local-order.enabled", "false"))
                .run()) {
            try (var connection = context.getBean(DataSource.class).getConnection()) {
                assertTrue(connection.isValid(2));
            }
            assertEquals(EngineState.RUNNING, context.getBean(Engine.class).state());
            assertEquals(1, context.getBeansOfType(AgentPackProductionPhasePreparation.class).size());
            assertEquals(2, context.getBeansOfType(VerificationEvidenceValidator.class).size());
            var tickEvidence = assertInstanceOf(ArtifactVerificationReviewEvidence.class,
                    context.getBean(VerificationEvidenceValidator.class));
            assertSame(tickEvidence, context.getBean(VerificationReviewEvidenceRecorder.class));
            assertInstanceOf(ArtifactVerificationEvidenceValidator.class,
                    context.getBean("fullVerificationEvidenceValidator"));
            assertTrue(context.getBeansOfType(CodingWorker.class).isEmpty());
            assertTrue(context.getBeansOfType(FactoryAgentPackLocalOrderRunner.class).isEmpty());
        }
    }
}
