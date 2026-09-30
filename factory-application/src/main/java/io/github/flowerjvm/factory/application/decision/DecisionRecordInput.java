package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Human-selected facts only. Authentication, authority and timestamps never come from this payload. */
public record DecisionRecordInput(DecisionPointId decisionPointId, long expectedDecisionPointVersion,
        ContentHash subjectHash, DecisionOutcome outcome, Optional<String> reason) {
    private static final Set<String> KEYS = Set.of("decisionPointId", "expectedDecisionPointVersion", "subjectHash", "outcome", "reason");
    public DecisionRecordInput {
        Objects.requireNonNull(decisionPointId, "decisionPointId"); text(decisionPointId.value(), 255);
        if (expectedDecisionPointVersion < 0 || expectedDecisionPointVersion == Long.MAX_VALUE) throw invalid();
        Objects.requireNonNull(subjectHash, "subjectHash"); Objects.requireNonNull(outcome, "outcome");
        reason = Objects.requireNonNull(reason, "reason"); reason.ifPresent(value -> text(value, 4096));
    }
    public static DecisionRecordInput from(Map<String, Object> input) {
        if (input == null || !input.keySet().equals(KEYS)) throw invalid();
        Object version = input.get("expectedDecisionPointVersion");
        if (!(version instanceof Byte || version instanceof Short || version instanceof Integer || version instanceof Long)) throw invalid();
        Object reason = input.get("reason");
        if (!(reason instanceof String)) throw invalid();
        if (!string(input.get("subjectHash")).matches("[0-9a-f]{64}")) throw invalid();
        return new DecisionRecordInput(new DecisionPointId(string(input.get("decisionPointId"))), ((Number) version).longValue(),
                new ContentHash(string(input.get("subjectHash"))), DecisionOutcome.valueOf(string(input.get("outcome"))),
                ((String) reason).isEmpty() ? Optional.empty() : Optional.of((String) reason));
    }
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decisionPointId", decisionPointId.value()); result.put("expectedDecisionPointVersion", expectedDecisionPointVersion);
        result.put("subjectHash", subjectHash.sha256()); result.put("outcome", outcome.name()); result.put("reason", reason.orElse(""));
        return java.util.Collections.unmodifiableMap(result);
    }
    static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || !value.equals(value.trim())) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.isLowSurrogate(c)) throw invalid();
            if (Character.isHighSurrogate(c) && (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))) throw invalid();
        }
        return value;
    }
    private static String string(Object value) { if (!(value instanceof String text)) throw invalid(); return text; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("DECISION_INPUT_INVALID"); }
}
