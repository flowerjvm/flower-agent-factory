package io.github.flowerjvm.factory.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.production.ActionBackedAgentPackProductionIntakeLauncher;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeActionExecutor;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeActionValidator;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeInputs;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeRecipeCheck;
import io.github.flowerjvm.factory.application.production.MaintenanceRepairDemoScenario;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakePolicyGate;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakePreExecutionGuard;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeService;
import io.github.flowerjvm.factory.application.production.AgentPackProductionIntakeVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.production.AgentPackProductionService;
import io.github.flowerjvm.factory.infrastructure.production.AgentPackProductionInputAssembler;
import io.github.flowerjvm.factory.infrastructure.verification.Pr4MavenToolchainInstaller;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit opt-in intake; no default profile, implicit login, API key or approval is provided. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "factory.production.agent-pack", name = "intake-enabled", havingValue = "true")
public class FactoryAgentPackProductionIntakeConfiguration {
    @Bean
    AgentPackProductionIntakeInputs agentPackProductionIntakeInputs(
            Pr4MavenToolchainInstaller installer, ObjectMapper mapper,
            AgentPackProductionWorkerSelection selection,
            @Value("${factory.production.agent-pack.flower-skill-root}") String skillRoot,
            @Value("${factory.production.agent-pack.demo-repair-scenario:}") String demoScenario) {
        MaintenanceRepairDemoScenario.recipeForSelection(demoScenario);
        Path root = Path.of(skillRoot);
        if (!root.isAbsolute()) throw new IllegalArgumentException("installed Flower skill root must be absolute");
        var assembler = new AgentPackProductionInputAssembler(installer, root.normalize(), mapper);
        return (tenant, session) -> {
            var binding = selection.snapshot();
            // One installed Coding Worker executes both phases. Manager metadata owns instructions;
            // it does not introduce another provider or an ungoverned model call.
            var prepared = assembler.prepare(tenant, session, selection.workspaceRef(), binding, binding, demoScenario);
            return new AgentPackProductionIntakeInputs.Bundle(prepared.plan(), prepared.artifacts());
        };
    }

    @Bean
    AgentPackProductionIntakeRecipeCheck agentPackProductionIntakeRecipeCheck(
            AgentPackProductionService service,
            @Value("${factory.production.agent-pack.demo-repair-scenario:}") String demoScenario) {
        String recipeId = MaintenanceRepairDemoScenario.recipeForSelection(demoScenario);
        return (tenant, input) -> service.requireIntakeRecipe(tenant, input, recipeId);
    }

    @Bean
    AgentPackProductionIntakeService agentPackProductionIntakeService(
            AgentPackProductionService service, AgentPackProductionIntakeInputs inputs, Clock factoryClock) {
        return new AgentPackProductionIntakeService(service, inputs, factoryClock);
    }

    @Bean
    AgentPackProductionIntakeActionExecutor agentPackProductionIntakeActionExecutor(AgentPackProductionIntakeService service) {
        return new AgentPackProductionIntakeActionExecutor(service);
    }

    @Bean
    AgentPackProductionIntakeActionValidator agentPackProductionIntakeActionValidator() {
        return new AgentPackProductionIntakeActionValidator();
    }

    @Bean
    AgentPackProductionIntakePolicyGate agentPackProductionIntakePolicyGate(BuildSessionRepository sessions, Clock factoryClock,
            AgentPackProductionIntakeRecipeCheck recipes) {
        return new AgentPackProductionIntakePolicyGate(sessions, factoryClock, recipes);
    }

    @Bean
    AgentPackProductionIntakePreExecutionGuard agentPackProductionIntakePreExecutionGuard(BuildSessionRepository sessions, Clock factoryClock,
            AgentPackProductionIntakeRecipeCheck recipes) {
        return new AgentPackProductionIntakePreExecutionGuard(sessions, factoryClock, recipes);
    }

    @Bean
    AgentPackProductionIntakeVisibilityScopeResolver agentPackProductionIntakeVisibilityScopeResolver(BuildSessionRepository sessions,
            @Value("${factory.production.agent-pack.demo-repair-scenario:}") String demoScenario) {
        return new AgentPackProductionIntakeVisibilityScopeResolver(sessions,
                MaintenanceRepairDemoScenario.recipeForSelection(demoScenario));
    }

    @Bean
    ActionBackedAgentPackProductionIntakeLauncher agentPackProductionIntakeLauncher(ActionRuntime factoryActionRuntime, Clock factoryClock) {
        return new ActionBackedAgentPackProductionIntakeLauncher(factoryActionRuntime, factoryClock);
    }
}
