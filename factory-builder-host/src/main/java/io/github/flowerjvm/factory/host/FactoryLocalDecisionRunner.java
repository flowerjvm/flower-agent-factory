package io.github.flowerjvm.factory.host;

import io.github.flowerjvm.factory.application.decision.ActionBackedDecisionRecordLauncher;
import io.github.flowerjvm.factory.application.decision.DecisionRecordInput;
import io.github.flowerjvm.flower.action.runtime.ActionExecutionStatus;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/** One explicit operator command; does not generate approval, certify, publish or alter a Flow. */
public final class FactoryLocalDecisionRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(FactoryLocalDecisionRunner.class);
    private final ActionBackedDecisionRecordLauncher launcher;
    private final Path operatorBindingPath;
    private final Path requestPath;
    private final Supplier<String> nativeSid;

    public FactoryLocalDecisionRunner(ActionBackedDecisionRecordLauncher launcher, Path operatorBindingPath, Path requestPath) {
        this(launcher, operatorBindingPath, requestPath, FactoryLocalDecisionOperator::currentWindowsSid);
    }

    FactoryLocalDecisionRunner(ActionBackedDecisionRecordLauncher launcher, Path operatorBindingPath, Path requestPath, Supplier<String> nativeSid) {
        this.launcher = Objects.requireNonNull(launcher);
        this.operatorBindingPath = Objects.requireNonNull(operatorBindingPath);
        this.requestPath = Objects.requireNonNull(requestPath);
        this.nativeSid = Objects.requireNonNull(nativeSid);
    }

    @Override
    public void run(ApplicationArguments ignored) {
        var operator = FactoryLocalDecisionOperator.authenticate(operatorBindingPath, nativeSid);
        var request = FactoryLocalDecisionDocuments.decode(FactoryLocalDecisionDocuments.read(requestPath),
                Set.of("schemaVersion", "requestKey", "decision"));
        if (!"factory.local-decision-request.v1".equals(request.get("schemaVersion"))
                || !(request.get("decision") instanceof Map<?, ?> decision)) throw FactoryLocalDecisionDocuments.invalid();
        @SuppressWarnings("unchecked") var input = DecisionRecordInput.from((Map<String, Object>) decision);
        var result = launcher.record(operator.authority(), operator.decisionPointId(),
                FactoryLocalDecisionDocuments.text(request.get("requestKey")), input);
        if (result.status() != ActionExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException("LOCAL_DECISION_NOT_RECORDED:" + result.status());
        }
        LOG.info("Exact operator decision recorded through factory.decision.record; production continuation owns certification and shipment");
    }
}
