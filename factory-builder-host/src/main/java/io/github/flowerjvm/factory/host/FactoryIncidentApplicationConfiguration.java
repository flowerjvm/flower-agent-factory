package io.github.flowerjvm.factory.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.certification.*;
import io.github.flowerjvm.factory.application.decision.DecisionPointRepository;
import io.github.flowerjvm.factory.application.flow.FactoryProductLineFlowLauncher;
import io.github.flowerjvm.factory.application.flow.FactoryFlowCancellationRecovery;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.infrastructure.incidentapplication.tool.DockerIncidentApplicationProductionTool;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcIncidentApplicationLedger;
import io.github.flowerjvm.flower.action.runtime.DefaultActionRuntime;
import io.github.flowerjvm.flower.action.runtime.run.RunStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;

/** Opt-in specialized product line; no implicit order, model invocation, human approval or deployment. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "factory.production.incident-application", name = "enabled", havingValue = "true")
public class FactoryIncidentApplicationConfiguration {
    private static final String PREFIX = "factory.production.incident-application.";

    @Bean IncidentApplicationProductionTool incidentApplicationProductionTool(ArtifactStore artifacts,
            @Qualifier("certifiedComponentResolver") CertifiedAgentComponentReadGate fullComponents, Environment environment) {
        return new DockerIncidentApplicationProductionTool(artifacts,fullComponents,path(environment,"catalog-root"),
                path(environment,"maven-repository"),path(environment,"workspace-root"),required(environment,"docker-executable"),Duration.ofSeconds(120));
    }

    @Bean @DependsOn("factoryFlyway")
    IncidentApplicationLedger incidentApplicationLedger(DataSource dataSource,ObjectMapper mapper,RunStore actionRunStore,
            @Qualifier("certifiedComponentResolver") CertifiedAgentComponentReadGate fullComponents,
            IncidentApplicationProductionTool tool,Clock factoryClock) {
        return new JdbcIncidentApplicationLedger(dataSource,mapper,actionRunStore,fullComponents,tool,factoryClock);
    }

    @Bean IncidentApplicationIntake incidentApplicationIntake(CertificationRepository certifications,
            @Qualifier("certifiedComponentResolver") CertifiedAgentComponentReadGate fullComponents,
            IncidentApplicationProductionTool tool,IncidentApplicationLedger ledger,Clock factoryClock) {
        return new IncidentApplicationIntake(certifications,fullComponents,tool,ledger,factoryClock);
    }

    @Bean IncidentApplicationActions incidentApplicationActions(IncidentApplicationLedger ledger,
            IncidentApplicationIntake intake,RunStore actionRunStore,Clock factoryClock) {
        return new IncidentApplicationActions(ledger,intake,actionRunStore,factoryClock);
    }

    @Bean IncidentApplicationReviewRenewalAction incidentApplicationReviewRenewalAction(IncidentApplicationLedger ledger,
            RunStore actionRunStore,Clock factoryClock) {
        return new IncidentApplicationReviewRenewalAction(ledger,actionRunStore,factoryClock);
    }

    @Bean IncidentApplicationLauncher incidentApplicationLauncher(DefaultActionRuntime factoryActionRuntime) {
        return new IncidentApplicationLauncher(factoryActionRuntime);
    }

    @Bean IncidentApplicationDecisionAuthority incidentApplicationDecisionAuthority(IncidentApplicationLedger ledger,Clock factoryClock) {
        return new IncidentApplicationDecisionAuthority(ledger,factoryClock);
    }

    @Bean IncidentApplicationFlowFactory incidentApplicationFlowFactory(IncidentApplicationLedger ledger,
            BuildSessionRepository sessions,DecisionPointRepository decisions,IncidentApplicationLauncher launcher,Clock factoryClock) {
        return new IncidentApplicationFlowFactory(ledger,sessions,decisions,launcher,factoryClock);
    }

    @Bean IncidentApplicationRunner incidentApplicationRunner(IncidentApplicationLedger ledger,
            IncidentApplicationProductionTool tool,@Qualifier("certifiedComponentResolver") CertifiedAgentComponentReadGate fullComponents,
            RunStore actionRunStore,DefaultActionRuntime factoryActionRuntime,Clock factoryClock) {
        return new IncidentApplicationRunner(ledger,tool,fullComponents,actionRunStore,factoryActionRuntime,factoryClock);
    }

    @Bean IncidentApplicationRecovery incidentApplicationRecovery(IncidentApplicationLedger ledger,
            BuildSessionRepository sessions,FactoryProductLineFlowLauncher launcher) {
        return new IncidentApplicationRecovery(ledger,sessions,launcher);
    }

    @Bean IncidentApplicationCancellationRequester incidentApplicationCancellation(IncidentApplicationLedger ledger,
            RunStore actionRunStore,DefaultActionRuntime factoryActionRuntime,Clock factoryClock) {
        return new IncidentApplicationCancellationRequester(ledger,actionRunStore,factoryActionRuntime,factoryClock);
    }

    @Bean(destroyMethod = "shutdown") ScheduledExecutorService incidentApplicationExecutor() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            var thread=new Thread(task,"factory-incident-application"); thread.setDaemon(true); return thread;
        });
    }

    @Bean IncidentApplicationPump incidentApplicationPump(IncidentApplicationRunner runner,IncidentApplicationRecovery recovery,
            @Qualifier("incidentApplicationExecutor") ScheduledExecutorService executor,
            @Qualifier("moduleOnlyFlowCancellationRecovery") ObjectProvider<FactoryFlowCancellationRecovery> cancellations) {
        return new IncidentApplicationPump(runner,recovery,executor,Duration.ofMillis(250),cancellations.getIfAvailable());
    }

    private static Path path(Environment environment,String name) {
        var path=Path.of(required(environment,name));
        if(!path.isAbsolute() || !path.equals(path.normalize())) throw new IllegalArgumentException(PREFIX+name+" must be absolute and normalized");
        return path;
    }
    private static String required(Environment environment,String name) {
        String value=environment.getProperty(PREFIX+name);
        if(value==null || value.isBlank() || !value.equals(value.trim())) throw new IllegalArgumentException(PREFIX+name+" must be explicitly configured");
        return value;
    }
}
