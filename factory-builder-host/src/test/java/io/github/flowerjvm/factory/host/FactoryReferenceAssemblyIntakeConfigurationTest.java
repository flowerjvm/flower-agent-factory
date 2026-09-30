package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.referenceassembly.*;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcReferenceAssemblyIntakeTransaction;
import io.github.flowerjvm.flower.action.runtime.*;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/** Full Host wiring on an empty synthetic H2 ledger, never actual intake/approval/component evidence. */
class FactoryReferenceAssemblyIntakeConfigurationTest {
    @Test void enabledIntakeRegistersAllControlledRoutesAndUsesTheFullComponentGate() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("assembly-intake-test",
                    Map.of("factory.production.reference-assembly.intake-enabled", "true")));
            var dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa"); dataSource.setPassword("");
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(FactoryActionRuntimeConfiguration.class, FactoryWorkerTransportConfiguration.class,
                    FactoryCertificationConfiguration.class, FactoryReferenceAssemblyConfiguration.class,
                    FactoryFlowerConfiguration.class, FactoryReferenceAssemblyIntakeConfiguration.class,
                    FactoryReferenceAssemblyLocalOrderConfiguration.class);
            context.refresh();
            assertTrue(context.getBeansOfType(FactoryReferenceAssemblyLocalOrderRunner.class).isEmpty());
            assertInstanceOf(JdbcReferenceAssemblyIntakeTransaction.class, context.getBean(ReferenceAssemblyIntakeTransaction.class));
            var service = context.getBean(ReferenceAssemblyIntakeService.class);
            assertSame(context.getBean("certifiedComponentResolver"), ReflectionTestUtils.getField(service, "fullResolver"));
            assertSame(context.getBean("certifiedComponentResolver"), ReflectionTestUtils.getField(context.getBean(ReferenceAssemblyIntakeTransaction.class), "componentReadGate"));
            for (String bean : new String[] {"actionInputValidator", "actionPolicyGate", "actionPreExecutionGuard"}) {
                var routes = (Map<?, ?>) ReflectionTestUtils.getField(context.getBean(bean), "routes");
                assertNotNull(routes); assertTrue(routes.containsKey(ReferenceAssemblyIntakeAction.ACTION_ID));
            }
            assertEquals(ReferenceAssemblyIntakeAction.ACTION_ID, context.getBean(ReferenceAssemblyIntakeActionExecutor.class).definition().actionId());
            assertNotNull(context.getBean(ReferenceAssemblyIntakeVisibilityScopeResolver.class));
            var result = context.getBean(ActionRuntime.class).handle(ActionProposal.builder(ReferenceAssemblyIntakeAction.ACTION_ID)
                    .proposalId("invalid-synthetic-intake").input(Map.of()).build(),
                    new ExecutionContext("tenant", "unauthorized", UUID.randomUUID().toString(), "trace", Map.of()));
            assertNotEquals(ActionExecutionStatus.SUCCEEDED, result.status());
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM factory_build_session")) {
                assertTrue(rows.next()); assertEquals(0, rows.getLong(1));
            } catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
        }
    }
}
