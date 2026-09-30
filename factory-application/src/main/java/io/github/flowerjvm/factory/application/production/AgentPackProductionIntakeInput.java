package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The payload carries no requester, tenant, permission, recipe or artifact authority. */
public record AgentPackProductionIntakeInput(
        BuildSessionId buildSessionId, ProjectId projectId, Instant deadlineAt, int maxRepairRounds) {
    private static final Set<String> FIELDS = Set.of("buildSessionId", "projectId", "deadlineAt", "maxRepairRounds");
    public AgentPackProductionIntakeInput {
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        Objects.requireNonNull(projectId, "projectId");
        boundedText(buildSessionId.value(), "buildSessionId");
        boundedText(projectId.value(), "projectId");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (!deadlineAt.equals(deadlineAt.truncatedTo(ChronoUnit.MICROS)) || maxRepairRounds < 0 || maxRepairRounds > 3) {
            throw new IllegalArgumentException("deadline precision or repair limit is invalid");
        }
    }

    public void requireLiveAt(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!deadlineAt.isAfter(now) || deadlineAt.isAfter(now.plus(Duration.ofHours(48)))) {
            throw new IllegalArgumentException("intake deadline must be in the next 48 hours");
        }
    }

    public static AgentPackProductionIntakeInput from(Map<String, Object> input) {
        if (input == null || !input.keySet().equals(FIELDS)) {
            throw new IllegalArgumentException("intake input must contain exactly its four declared fields");
        }
        Object rounds = input.get("maxRepairRounds");
        if (!(input.get("buildSessionId") instanceof String session)
                || !(input.get("projectId") instanceof String project)
                || !(input.get("deadlineAt") instanceof String deadline)
                || !(rounds instanceof Byte || rounds instanceof Short || rounds instanceof Integer || rounds instanceof Long)
                || ((Number) rounds).longValue() < 0 || ((Number) rounds).longValue() > 3) {
            throw new IllegalArgumentException("intake requires typed ids, ISO instant and an integer repair limit");
        }
        final Instant parsed;
        try { parsed = Instant.parse(deadline); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("intake deadline is invalid"); }
        if (!parsed.toString().equals(deadline)) throw new IllegalArgumentException("intake deadline must be canonical UTC");
        return new AgentPackProductionIntakeInput(new BuildSessionId(session), new ProjectId(project), parsed,
                ((Number) rounds).intValue());
    }

    public Map<String, Object> toMap() {
        return Map.of("buildSessionId", buildSessionId.value(), "projectId", projectId.value(),
                "deadlineAt", deadlineAt.toString(), "maxRepairRounds", maxRepairRounds);
    }

    static String boundedText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 255 || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be bounded non-control text");
        }
        // UTF-8 encoders replace lone surrogates. Reject them before identifiers enter a
        // content hash, durable acceptance receipt or duplicate-visibility scope.
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException(field + " must be well-formed Unicode");
                }
                i++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(field + " must be well-formed Unicode");
            }
        }
        return value;
    }
}
