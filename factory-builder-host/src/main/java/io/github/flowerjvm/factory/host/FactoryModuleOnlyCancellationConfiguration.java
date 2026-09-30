package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.flow.*;
import io.github.flowerjvm.factory.application.work.WorkerRunRepository;
import io.github.flowerjvm.flower.core.engine.Engine;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Installed deterministic product lines do not need a Coding Worker to cancel their own effects. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="factory.production.incident-application",name="enabled",havingValue="true")
public class FactoryModuleOnlyCancellationConfiguration {
    @Bean
    @ConditionalOnProperty(prefix="factory.worker.codex",name="enabled",havingValue="false",matchIfMissing=true)
    FactoryFlowCancellationService moduleOnlyFlowCancellationService(BuildSessionRepository sessions,Engine factoryFlowerEngine,
            FactoryFlowRegistry flows,Clock factoryClock,List<FactoryProductLineCancellationRequester> requesters) {
        return new FactoryFlowCancellationService(sessions,factoryFlowerEngine,flows,factoryClock,requesters);
    }

    @Bean
    @ConditionalOnProperty(prefix="factory.worker.codex",name="enabled",havingValue="false",matchIfMissing=true)
    FactoryFlowCancellationRecovery moduleOnlyFlowCancellationRecovery(BuildSessionRepository sessions,WorkerRunRepository workerRuns,
            FactoryFlowCancellationService cancellations,Clock factoryClock) {
        return new FactoryFlowCancellationRecovery(sessions,workerRuns,cancellations,factoryClock);
    }
}
