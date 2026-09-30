package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.action.ReferenceAssemblyReleaseActionExecutor;
import io.github.flowerjvm.factory.application.decision.DecisionRecordingService;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineFlowLauncher;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineRegistry;
import io.github.flowerjvm.factory.application.flow.FactoryReferenceAssemblyFlowRecovery;
import io.github.flowerjvm.factory.application.flow.ReferenceAssemblyFlowFactory;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyProductLineCatalog;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyAdmissionPolicies;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyAssembler;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyInspector;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyRequestService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseReviewService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseService;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseAuthorityVerifier;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyCancellationRequester;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseDispatchTransaction;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyReleaseTransaction;
import io.github.flowerjvm.factory.application.referenceassembly.ReleasedReferenceAssemblyReadGate;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcReferenceAssemblyReleaseDispatchTransaction;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcReferenceAssemblyReleaseTransaction;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.core.recovery.FlowFactoryRegistry;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

class FactoryReferenceAssemblyConfigurationTest {
    @Test
    void fullHostSeparatesReferenceAssemblyTickReadsFromFinalReleaseAndPublicReadGate() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, FactoryReferenceAssemblyConfigurationTest::dataSource);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(
                    FactoryActionRuntimeConfiguration.class,
                    FactoryWorkerTransportConfiguration.class,
                    FactoryCertificationConfiguration.class,
                    FactoryReferenceAssemblyConfiguration.class,
                    FactoryFlowerConfiguration.class);
            context.refresh();

            FactoryProductLineRegistry productLines =
                    context.getBean(FactoryProductLineRegistry.class);
            assertEquals(
                    java.util.Set.of(ProductLineId.AGENT_PACK, ProductLineId.REFERENCE_ASSEMBLY),
                    productLines.productLineIds());
            assertInstanceOf(
                    ReferenceAssemblyFlowFactory.class,
                    productLines.require(ProductLineId.REFERENCE_ASSEMBLY));
            assertTrue(context.getBean(FlowFactoryRegistry.class)
                    .contains(ReferenceAssemblyFlowFactory.FLOW_TYPE));
            context.getBean(FactoryProductLineFlowLauncher.class);
            context.getBean(FactoryReferenceAssemblyFlowRecovery.class);

            assertInstanceOf(
                    JdbcReferenceAssemblyReleaseDispatchTransaction.class,
                    context.getBean(ReferenceAssemblyReleaseDispatchTransaction.class));
            assertInstanceOf(
                    JdbcReferenceAssemblyReleaseTransaction.class,
                    context.getBean(ReferenceAssemblyReleaseTransaction.class));
            context.getBean(ReferenceAssemblyReleaseActionExecutor.class);
            context.getBean(ReferenceAssemblyCancellationRequester.class);
            context.getBean(ReleasedReferenceAssemblyReadGate.class);
            var catalog = context.getBean(ReferenceAssemblyProductLineCatalog.class);
            var policies = context.getBean(ReferenceAssemblyAdmissionPolicies.class);
            for (var entry : ReferenceAssemblyProductLineCatalog.Entry.values()) {
                assertEquals(catalog.admissionPolicy(entry),
                        policies.find(catalog.consumerContractLock(entry)).orElseThrow());
            }
            for (Object consumer : java.util.List.of(
                    context.getBean(ReferenceAssemblyRequestService.class),
                    context.getBean(ReferenceAssemblyAssembler.class),
                    context.getBean(ReferenceAssemblyInspector.class),
                    context.getBean(ReferenceAssemblyReleaseReviewService.class),
                    context.getBean(ReleasedReferenceAssemblyReadGate.class))) {
                assertSame(policies, ReflectionTestUtils.getField(consumer, "admissionPolicies"));
            }
            Object tickComponents = context.getBean("tickComponentReadGate");
            Object fullComponents = context.getBean("certifiedComponentResolver");
            for (Object tickConsumer : java.util.List.of(
                    context.getBean(ReferenceAssemblyAssembler.class),
                    context.getBean(ReferenceAssemblyInspector.class),
                    context.getBean(ReferenceAssemblyReleaseReviewService.class))) {
                assertSame(tickComponents, ReflectionTestUtils.getField(tickConsumer, "componentReadGate"));
            }
            assertSame(context.getBean(ReferenceAssemblyAssembler.class), ReflectionTestUtils.getField(
                    context.getBean(ReferenceAssemblyReleaseAuthorityVerifier.class), "assembler"));
            assertSame(fullComponents, ReflectionTestUtils.getField(
                    context.getBean(ReferenceAssemblyReleaseService.class), "fullComponents"));
            assertSame(fullComponents, ReflectionTestUtils.getField(
                    context.getBean(ReleasedReferenceAssemblyReadGate.class), "componentReadGate"));
            context.getBean(DefaultActionRuntime.class);

            assertTrue(context.getBean(ReferenceAssemblyReleaseDispatchPump.class).isRunning());
            assertTrue(context.getBean(ReferenceAssemblyFlowRecoveryPump.class).isRunning());
            @SuppressWarnings("unchecked")
            Map<String, ?> authorities = (Map<String, ?>) ReflectionTestUtils.getField(
                    context.getBean(DecisionRecordingService.class), "subjectAuthorities");
            assertEquals(2, authorities.size());
        }
    }

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
