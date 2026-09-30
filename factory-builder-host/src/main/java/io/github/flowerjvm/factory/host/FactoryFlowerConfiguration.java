package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.flow.CreateCustomerAgentFlowCoordinator;
import io.github.flowerjvm.factory.application.flow.CreateCustomerAgentFlowFactory;
import io.github.flowerjvm.factory.application.flow.FactoryContinuationFlow;
import io.github.flowerjvm.factory.application.flow.FactoryContinuationFlowLauncher;
import io.github.flowerjvm.factory.application.flow.FactoryCertificationContinuationRecovery;
import io.github.flowerjvm.factory.application.flow.FactoryFlowRegistry;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineFlowLauncher;
import io.github.flowerjvm.factory.application.flow.FactoryProductLine;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineRegistry;
import io.github.flowerjvm.factory.application.flow.FactoryReferenceAssemblyFlowRecovery;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.flower.core.engine.Engine;
import io.github.flowerjvm.flower.core.event.InMemoryEventBus;
import io.github.flowerjvm.flower.core.persistence.FlowCheckpointStore;
import io.github.flowerjvm.flower.core.recovery.FlowFactoryRegistry;
import io.github.flowerjvm.flower.core.recovery.FlowRecoveryService;
import io.github.flowerjvm.flower.core.worker.DuplicatePolicy;
import io.github.flowerjvm.flower.core.worker.Worker;
import io.github.flowerjvm.flower.core.time.SystemClock;
import io.github.flowerjvm.flower.persistence.jdbc.JdbcCheckpointDialect;
import io.github.flowerjvm.flower.persistence.jdbc.JdbcCheckpointDialects;
import io.github.flowerjvm.flower.persistence.jdbc.JdbcFlowCheckpointStore;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** PR3 single-host recovery wiring. It deliberately adds no second Worker or EventLoop backend. */
@Configuration(proxyBeanMethods = false)
public class FactoryFlowerConfiguration {
    public static final String FACTORY_WORKER = "factory-builder";

    @Bean
    @DependsOn("factoryFlyway")
    FlowCheckpointStore factoryFlowCheckpointStore(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            JdbcCheckpointDialect dialect;
            if (product.contains("postgresql")) {
                dialect = JdbcCheckpointDialects.postgresql();
            } else if (product.equals("h2")) {
                dialect = JdbcCheckpointDialects.h2();
            } else {
                throw new IllegalStateException("Factory PR3 supports PostgreSQL and H2, found " + product);
            }
            return JdbcFlowCheckpointStore.create(dataSource, dialect);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to select Flower checkpoint dialect", exception);
        }
    }

    @Bean
    Engine factoryFlowerEngine(FlowCheckpointStore checkpointStore) {
        return Engine.builder()
                .clock(SystemClock.INSTANCE)
                .eventBus(InMemoryEventBus.create())
                .worker(Worker.builder(FACTORY_WORKER).intervalMillis(100L).build())
                .checkpointStore(checkpointStore)
                .build();
    }

    @Bean
    CreateCustomerAgentFlowFactory createCustomerAgentFlowFactory(
            CreateCustomerAgentFlowCoordinator coordinator) {
        return new CreateCustomerAgentFlowFactory(coordinator);
    }

    @Bean
    FactoryProductLineRegistry factoryProductLineRegistry(List<FactoryProductLine> productLines) {
        return new FactoryProductLineRegistry(productLines);
    }

    @Bean
    FactoryFlowRegistry factoryFlowRegistry(
            FactoryProductLineRegistry productLines,
            List<FactoryContinuationFlow> continuations) {
        return new FactoryFlowRegistry(productLines, continuations);
    }

    @Bean
    FlowFactoryRegistry flowFactoryRegistry(FactoryFlowRegistry flows) {
        return flows.flowFactoryRegistry();
    }

    @Bean
    FactoryContinuationFlowLauncher factoryContinuationFlowLauncher(
            BuildSessionRepository sessions,
            FactoryFlowRegistry flows,
            Engine engine) {
        return new FactoryContinuationFlowLauncher(sessions, flows, engine, FACTORY_WORKER);
    }

    @Bean
    FactoryProductLineFlowLauncher factoryProductLineFlowLauncher(
            BuildSessionRepository sessions,
            FactoryProductLineRegistry productLines,
            Engine engine) {
        return new FactoryProductLineFlowLauncher(
                sessions, productLines, engine, FACTORY_WORKER);
    }

    @Bean
    FactoryCertificationContinuationRecovery factoryCertificationContinuationRecovery(
            BuildSessionRepository sessions,
            FactoryContinuationFlowLauncher launcher,
            Clock factoryClock) {
        return new FactoryCertificationContinuationRecovery(sessions, launcher, factoryClock);
    }

    @Bean
    FactoryReferenceAssemblyFlowRecovery factoryReferenceAssemblyFlowRecovery(
            BuildSessionRepository sessions,
            FactoryProductLineFlowLauncher launcher,
            Clock factoryClock) {
        return new FactoryReferenceAssemblyFlowRecovery(sessions, launcher, factoryClock);
    }

    @Bean
    @DependsOn("factoryFlyway")
    SmartInitializingSingleton recoverFactoryFlows(
            Engine engine,
            FlowCheckpointStore checkpointStore,
            FlowFactoryRegistry flowFactoryRegistry,
            FactoryCertificationContinuationRecovery certificationRecovery,
            FactoryReferenceAssemblyFlowRecovery referenceAssemblyRecovery,
            ObjectProvider<FactoryIncidentApplicationLocalReviewRenewal> localReviewRenewal) {
        return () -> {
            // Exact opt-in operator control must finish before expired checkpoints can resume.
            // A denial/uncertainty aborts startup; it never silently falls through to attach.
            localReviewRenewal.ifAvailable(FactoryIncidentApplicationLocalReviewRenewal::execute);
            engine.attach();
            FlowRecoveryService.create(checkpointStore, flowFactoryRegistry)
                    .recoverActiveForWorker(engine.worker(FACTORY_WORKER), DuplicatePolicy.REJECT);
            certificationRecovery.recoverBatch();
            referenceAssemblyRecovery.recoverBatch();
        };
    }
}
