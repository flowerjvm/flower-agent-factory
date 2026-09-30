package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.decision.ActionBackedDecisionRecordLauncher;
import io.github.flowerjvm.factory.application.decision.AgentPackReleaseReviewDecisionSubjectAuthority;
import io.github.flowerjvm.factory.application.decision.DecisionPointRepository;
import io.github.flowerjvm.factory.application.decision.DecisionRecordActionExecutor;
import io.github.flowerjvm.factory.application.decision.DecisionRecordActionValidator;
import io.github.flowerjvm.factory.application.decision.DecisionRecordAdmission;
import io.github.flowerjvm.factory.application.decision.DecisionRecordPolicyGate;
import io.github.flowerjvm.factory.application.decision.DecisionRecordPreExecutionGuard;
import io.github.flowerjvm.factory.application.decision.DecisionRecordVisibilityScopeResolver;
import io.github.flowerjvm.factory.application.decision.DecisionRecordingService;
import io.github.flowerjvm.factory.application.decision.DecisionRepository;
import io.github.flowerjvm.factory.application.decision.DecisionSubjectAuthority;
import io.github.flowerjvm.factory.application.decision.ReferenceAssemblyReleaseDecisionSubjectAuthority;
import io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationDecisionAuthority;
import io.github.flowerjvm.flower.action.runtime.ActionRuntime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the governed decision boundary, not a human identity source or automatic approval. */
@Configuration(proxyBeanMethods = false)
public class FactoryDecisionConfiguration {
    @Bean
    List<DecisionSubjectAuthority> factoryDecisionSubjectAuthorities(
            ObjectProvider<ReferenceAssemblyReleaseDecisionSubjectAuthority> referenceAssemblyAuthority,
            ObjectProvider<IncidentApplicationDecisionAuthority> incidentApplicationAuthority) {
        List<DecisionSubjectAuthority> authorities = new ArrayList<>();
        authorities.add(new AgentPackReleaseReviewDecisionSubjectAuthority());
        referenceAssemblyAuthority.ifAvailable(authorities::add);
        incidentApplicationAuthority.ifAvailable(authorities::add);
        return List.copyOf(authorities);
    }

    @Bean
    DecisionRecordAdmission decisionRecordAdmission(
            BuildSessionRepository sessions, DecisionPointRepository points, DecisionRepository decisions,
            @Qualifier("factoryDecisionSubjectAuthorities") List<DecisionSubjectAuthority> authorities,
            Clock factoryClock) {
        return new DecisionRecordAdmission(sessions, points, decisions, authorities, factoryClock);
    }

    @Bean
    DecisionRecordActionValidator decisionRecordActionValidator() {
        return new DecisionRecordActionValidator();
    }

    @Bean
    DecisionRecordPolicyGate decisionRecordPolicyGate(DecisionRecordAdmission admission) {
        return new DecisionRecordPolicyGate(admission);
    }

    @Bean
    DecisionRecordPreExecutionGuard decisionRecordPreExecutionGuard(DecisionRecordAdmission admission) {
        return new DecisionRecordPreExecutionGuard(admission);
    }

    @Bean
    DecisionRecordVisibilityScopeResolver decisionRecordVisibilityScopeResolver(DecisionRecordAdmission admission) {
        return new DecisionRecordVisibilityScopeResolver(admission);
    }

    @Bean
    DecisionRecordActionExecutor decisionRecordActionExecutor(
            DecisionRecordingService recordingService, DecisionRecordAdmission admission) {
        return new DecisionRecordActionExecutor(recordingService, admission);
    }

    @Bean
    ActionBackedDecisionRecordLauncher actionBackedDecisionRecordLauncher(ActionRuntime runtime, Clock factoryClock) {
        return new ActionBackedDecisionRecordLauncher(runtime, factoryClock);
    }
}
