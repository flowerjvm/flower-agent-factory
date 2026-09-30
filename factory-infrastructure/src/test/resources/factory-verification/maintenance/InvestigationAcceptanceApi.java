package io.github.flowerjvm.pack.maintenance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Native-test candidate implementation, independent of Factory golden cases. */
public final class InvestigationAcceptanceApi {
    public static Map<String, Object> investigate(Map<String, Object> incident) {
        try {
            if (incident == null || !incident.keySet().equals(Set.of("incidentId", "service", "summary", "observations"))) {
                return Map.of("errorCode", "INVALID_INCIDENT");
            }
            String incidentId = text(incident.get("incidentId"), 128);
            String service = text(incident.get("service"), 128);
            String summary = text(incident.get("summary"), 1024);
            if (!(incident.get("observations") instanceof List<?> raw) || raw.size() > 64) {
                return Map.of("errorCode", "INVALID_INCIDENT");
            }
            var normalized = new ArrayList<Map<String, Object>>();
            for (Object value : raw) {
                if (!(value instanceof Map<?, ?> observation)
                        || !observation.keySet().equals(Set.of("evidenceId", "source", "observedAt", "kind", "message"))) {
                    return Map.of("errorCode", "INVALID_INCIDENT");
                }
                normalized.add(Map.of(
                        "evidenceId", text(observation.get("evidenceId"), 128),
                        "source", text(observation.get("source"), 128),
                        "observedAt", Instant.parse(text(observation.get("observedAt"), 128)).toString(),
                        "kind", text(observation.get("kind"), 128),
                        "message", text(observation.get("message"), 1024)));
            }
            var byId = new LinkedHashMap<String, Map<String, Object>>();
            for (var observation : normalized) {
                String id = (String) observation.get("evidenceId");
                var previous = byId.putIfAbsent(id, observation);
                if (previous != null && !previous.equals(observation)) {
                    return Map.of("errorCode", "EVIDENCE_ID_CONFLICT");
                }
            }
            var evidence = new ArrayList<>(byId.values());
            evidence.sort(Comparator.<Map<String, Object>, Instant>comparing(value ->
                            Instant.parse((String) value.get("observedAt")))
                    .thenComparing(value -> (String) value.get("evidenceId")));
            var groups = new TreeMap<String, TreeSet<String>>();
            for (var observation : evidence) {
                String message = ((String) observation.get("message")).toLowerCase(Locale.ROOT);
                String code = message.contains("timeout") ? "SERVICE_TIMEOUT"
                        : message.contains("5xx") || message.contains("error rate") ? "ERROR_RATE_ELEVATED"
                        : "OBSERVATION_REQUIRES_REVIEW";
                groups.computeIfAbsent(code, ignored -> new TreeSet<>()).add((String) observation.get("evidenceId"));
            }
            var findings = new ArrayList<Map<String, Object>>();
            for (var entry : groups.entrySet()) {
                var finding = new LinkedHashMap<String, Object>();
                finding.put("code", entry.getKey());
                finding.put("severity", entry.getKey().equals("OBSERVATION_REQUIRES_REVIEW") ? "MEDIUM" : "HIGH");
                finding.put("evidenceRefs", new ArrayList<>(entry.getValue()));
                findings.add(finding);
            }
            var report = new StringBuilder("# Incident " + incidentId + "\nService: " + service
                    + "\nSummary: " + summary + "\n\n## Findings\n");
            if (findings.isEmpty()) report.append("- None\n");
            for (var finding : findings) {
                @SuppressWarnings("unchecked") var refs = (List<String>) finding.get("evidenceRefs");
                report.append("- ").append(finding.get("code")).append(" [").append(finding.get("severity"))
                        .append("] evidence: ").append(String.join(", ", refs)).append('\n');
            }
            report.append("\n## Evidence\n");
            if (evidence.isEmpty()) report.append("- None\n");
            for (var observation : evidence) {
                report.append("- ").append(observation.get("evidenceId")).append(" | ")
                        .append(observation.get("observedAt")).append(" | ").append(observation.get("source"))
                        .append(" | ").append(observation.get("kind")).append(" | ").append(observation.get("message")).append('\n');
            }
            /*FACTORY_TEST_MUTATION*/
            return Map.of("incidentId", incidentId, "service", service, "summary", summary,
                    "evidence", evidence, "findings", findings, "reportMarkdown", report.toString());
        } catch (RuntimeException invalid) {
            return Map.of("errorCode", "INVALID_INCIDENT");
        }
    }

    private static String text(Object value, int maximum) {
        if (!(value instanceof String raw) || raw.length() > maximum) throw new IllegalArgumentException();
        String result = raw.trim();
        if (result.isEmpty()) throw new IllegalArgumentException();
        for (int index = 0; index < result.length(); index++) {
            char ch = result.charAt(index);
            if (Character.isISOControl(ch)) throw new IllegalArgumentException();
            if (Character.isHighSurrogate(ch)) {
                if (++index == result.length() || !Character.isLowSurrogate(result.charAt(index))) throw new IllegalArgumentException();
            } else if (Character.isLowSurrogate(ch)) throw new IllegalArgumentException();
        }
        return result;
    }
}
