package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record AgentPackProductionInput(BuildSessionId buildSessionId, long expectedSessionVersion) {
    private static final Set<String> FIELDS = Set.of(
            AgentPackProductionAction.BUILD_SESSION_ID, AgentPackProductionAction.EXPECTED_SESSION_VERSION);

    public AgentPackProductionInput {
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        String id = buildSessionId.value();
        if (id.length() > 255 || !id.equals(id.trim()) || id.chars().anyMatch(Character::isISOControl)
                || expectedSessionVersion < 0) {
            throw new IllegalArgumentException("production session id or expected version is invalid");
        }
    }

    public static AgentPackProductionInput from(Map<String, Object> input) {
        if (input == null || !input.keySet().equals(FIELDS)) {
            throw new IllegalArgumentException("production input must contain exactly its two declared fields");
        }
        Object id = input.get(AgentPackProductionAction.BUILD_SESSION_ID);
        Object version = input.get(AgentPackProductionAction.EXPECTED_SESSION_VERSION);
        if (!(id instanceof String text) || text.isBlank()
                || !(version instanceof Byte || version instanceof Short || version instanceof Integer || version instanceof Long)) {
            throw new IllegalArgumentException("production input requires a session id and integer version");
        }
        return new AgentPackProductionInput(new BuildSessionId(text), ((Number) version).longValue());
    }

    public Map<String, Object> toMap() {
        return Map.of(AgentPackProductionAction.BUILD_SESSION_ID, buildSessionId.value(),
                AgentPackProductionAction.EXPECTED_SESSION_VERSION, expectedSessionVersion);
    }
}
