package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.util.List;
import java.util.Objects;

/** Read-only, bounded assembly on the intake caller, never on a Flower Worker tick. */
@FunctionalInterface
public interface AgentPackProductionIntakeInputs {
    Bundle prepare(TenantId tenantId, BuildSessionId buildSessionId);

    record Bundle(AgentPackProductionPlan plan, List<Artifact> artifacts) {
        public Bundle {
            Objects.requireNonNull(plan, "plan");
            artifacts = List.copyOf(artifacts);
            if (artifacts.size() > 62) throw new IllegalArgumentException("too many production input artifacts");
        }
    }
}
