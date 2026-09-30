package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.flower.action.runtime.ActionProposal;
import io.github.flowerjvm.flower.action.runtime.ExecutionContext;
import io.github.flowerjvm.flower.action.runtime.duplicate.DuplicateVisibilityScopeResolver;
import java.util.Objects;

/** Result IDs are private to a repository-verified BuildSession, never tenant-global. */
public final class AgentPackProductionVisibilityScopeResolver implements DuplicateVisibilityScopeResolver {
    private final BuildSessionRepository sessions;
    public AgentPackProductionVisibilityScopeResolver(BuildSessionRepository sessions) { this.sessions = Objects.requireNonNull(sessions, "sessions"); }

    @Override
    public String resolve(ActionProposal proposal, ExecutionContext context) {
        var canonical = AgentPackProductionAuthority.resource(sessions, AgentPackProductionInput.from(proposal.input()), context);
        return "build-session:" + canonical.buildSessionId().value();
    }
}
