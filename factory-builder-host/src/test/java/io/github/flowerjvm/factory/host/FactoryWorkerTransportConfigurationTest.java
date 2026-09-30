package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.flow.FactoryFlowCancellationService;
import io.github.flowerjvm.factory.contracts.worker.CodingWorker;
import io.github.flowerjvm.factory.infrastructure.worker.codex.CodexCodingWorker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class FactoryWorkerTransportConfigurationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void enabledCodexBindingWiresTheDurableTransportPumpAndGovernedCancellation() throws Exception {
        Path node = Files.createFile(temporaryDirectory.resolve("node-test-binary"));
        Path runner = Files.createFile(temporaryDirectory.resolve("runner-test.mjs"));
        Path state = Files.createDirectory(temporaryDirectory.resolve("state"));
        Path workspaceBase = Files.createDirectory(temporaryDirectory.resolve("workspaces"));
        Path workspace = Files.createDirectory(workspaceBase.resolve("candidate"));
        Path codexHome = Files.createDirectory(temporaryDirectory.resolve("codex-home"));

        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "codex-worker-test",
                    Map.of(
                            "factory.worker.codex.enabled", "true",
                            "factory.worker.codex.binding-id", "codex-test",
                            "factory.worker.codex.node-executable", node.toString(),
                            "factory.worker.codex.runner-entrypoint", runner.toString(),
                            "factory.worker.codex.state-root", state.toString(),
                            "factory.worker.codex.workspace-base", workspaceBase.toString(),
                            "factory.worker.codex.workspace-ref", "workspace:test",
                            "factory.worker.codex.workspace-path", workspace.toString(),
                            "factory.worker.codex.codex-home", codexHome.toString(),
                            "factory.worker.codex.process-timeout", "PT1S")));
            context.registerBean(DataSource.class, FactoryWorkerTransportConfigurationTest::dataSource);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(
                    FactoryActionRuntimeConfiguration.class,
                    FactoryFlowerConfiguration.class,
                    FactoryWorkerTransportConfiguration.class);
            context.refresh();

            assertInstanceOf(CodexCodingWorker.class, context.getBean(CodingWorker.class));
            assertTrue(context.getBean(WorkerTransportPump.class).isRunning());
            assertInstanceOf(
                    FactoryFlowCancellationService.class,
                    context.getBean(FactoryFlowCancellationService.class));
        }
    }

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:worker_transport_config_"
                + java.util.UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
