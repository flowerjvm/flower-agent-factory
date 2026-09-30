package io.github.flowerjvm.factory.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.flowerjvm.factory.application.FactoryApplicationModule;
import io.github.flowerjvm.factory.contracts.FactoryContractsModule;
import io.github.flowerjvm.factory.infrastructure.FactoryInfrastructureModule;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;

class ModuleDependencyArchitectureTest {
    @Test
    void hostCanSeeOnlyItsDeclaredInnerLayers() throws Exception {
        assertEquals(FactoryContractsModule.class, Class.forName(FactoryContractsModule.class.getName()));
        assertEquals(FactoryApplicationModule.class, Class.forName(FactoryApplicationModule.class.getName()));
        assertEquals(FactoryInfrastructureModule.class, Class.forName(FactoryInfrastructureModule.class.getName()));
    }

    @Test
    void prTwoAddsWiringButStillHasNoExecutableSpringEntryPoint() {
        assertEquals(0, FactoryBuilderHostModule.class.getAnnotations().length);
        assertEquals(
                Configuration.class,
                FactoryActionRuntimeConfiguration.class.getAnnotation(Configuration.class).annotationType());
    }
}
