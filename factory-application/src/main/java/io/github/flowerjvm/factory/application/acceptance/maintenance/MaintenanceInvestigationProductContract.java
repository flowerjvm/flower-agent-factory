package io.github.flowerjvm.factory.application.acceptance.maintenance;

import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Code-owned requirements and golden cases for one bounded Maintenance Investigation Pack core.
 *
 * <p>This is a product-specific inspection bridge, not an Agent Runtime ABI. It does not replace or
 * change the historical flower-agent-pack 1.0.0 contract. Golden outputs are specified independently
 * of the candidate implementation; this class deliberately contains no investigation algorithm.
 */
public final class MaintenanceInvestigationProductContract {
    public static final String CONTRACT_ID = "maintenance-investigation-pack";
    public static final String CONTRACT_VERSION = "1.0.0";
    public static final String GATE_PROFILE = "factory-maintenance-investigation-v1";
    public static final String API_CLASS_NAME =
            "io.github.flowerjvm.pack.maintenance.InvestigationAcceptanceApi";
    public static final String API_METHOD_NAME = "investigate";
    public static final String CASE_SUITE_SCHEMA = "factory.maintenance-investigation-case-suite.v1";
    public static final String CANONICAL_JSON_ALGORITHM_ID = "factory-maintenance-canonical-json.v1";
    public static final String INVALID_INCIDENT = "INVALID_INCIDENT";
    public static final String EVIDENCE_ID_CONFLICT = "EVIDENCE_ID_CONFLICT";
    public static final int MAX_INPUT_BYTES = 65_536;
    public static final int MAX_OUTPUT_BYTES = 131_072;
    public static final int MAX_OBSERVATIONS = 64;
    public static final int MAX_IDENTIFIER_LENGTH = 128;
    public static final int MAX_TEXT_LENGTH = 1_024;

    private static final List<AcceptanceCase> CASES = goldenCases();
    private static final Material REQUIREMENTS = material("requirements", "text/markdown; charset=utf-8", """
            # Maintenance Investigation Pack core acceptance v1

            This bounded deterministic transformation characterizes an Agent Pack's investigation core.
            It does not define a general Agent Runtime, provider, deployment, or operational API.
            Requirements derive from the documented incident -> evidence -> finding -> report slice;
            previous S0 generated source is not part of this contract or its implementation.
            Contract JSON uses factory-maintenance-canonical-json.v1: UTF-8 without BOM or trailing
            whitespace, object keys sorted by Java String.compareTo, array order preserved, decimal
            integers without leading zeroes, standard JSON escapes and lowercase four-digit Unicode
            escapes for controls/surrogates. Requirements Markdown and report templates use LF.

            ## Input and bounds
            The bridge accepts one Map<String,Object> with exactly incidentId, service, summary,
            observations. observations is a List of 0 through 64 Maps, each with exactly evidenceId,
            source, observedAt, kind, message. Unknown or missing fields at either level are invalid.
            All scalar values must be Strings. Nulls, wrong types, and blank strings are invalid.
            Every raw identifier (incidentId, service, evidenceId, source, kind, observedAt) has at
            most 128 UTF-16 code units; raw summary/message has at most 1024 UTF-16 code units.
            Trim every String using Java String.trim(). After trimming, strings must be nonempty,
            well-formed Unicode and contain no Character.isISOControl character. Do not infer tenant,
            credentials, addresses, or data not present in this sanitized input. The serialized UTF-8
            input is bounded to 65536 bytes and output to 131072 bytes by the inspection harness.
            An oversized transport document is rejected by the harness before candidate invocation.
            Any bridge-level shape, scalar, length, cardinality, or timestamp error returns exactly
            {"errorCode":"INVALID_INCIDENT"}; do not throw or return a partial success result.
            Validate all observations before applying the duplicate-conflict check, so an invalid
            observation takes precedence over EVIDENCE_ID_CONFLICT.

            ## Evidence and findings
            Parse observedAt using java.time.Instant.parse and render with Instant.toString.
            Evidence is the normalized observation list, sorted by Instant then evidenceId using
            String.compareTo. Collapse repeated evidenceId only when all normalized fields match.
            Different normalized content for the same evidenceId returns exactly
            {"errorCode":"EVIDENCE_ID_CONFLICT"}. Do not include findings or a report on errors.
            Classify each normalized message with Locale.ROOT case-insensitive substring checks:
            timeout -> SERVICE_TIMEOUT/HIGH; otherwise 5xx or error rate -> ERROR_RATE_ELEVATED/HIGH;
            otherwise OBSERVATION_REQUIRES_REVIEW/MEDIUM. Timeout has precedence when both match.
            Group by code; sort findings by code with String.compareTo. A finding contains exactly
            code, severity, evidenceRefs; references are sorted unique evidenceId strings, and every
            reference must name normalized evidence in this result. No finding may have no references.

            ## Success output and report bytes
            Return exactly incidentId, service, summary, evidence, findings, reportMarkdown.
            The first three values are the normalized input strings. evidence entries have exactly
            evidenceId, source, observedAt, kind, message. Empty observations is valid and produces
            empty evidence/findings arrays. Map property order is insignificant; list order is exact.
            reportMarkdown follows the template below, with LF only and one final LF. Substitute
            normalized strings literally, without Markdown escaping or added prose. Each finding
            line lists references joined by comma plus one space; each evidence line follows evidence
            ordering. Empty Findings or Evidence contains exactly '- None' on one line.

            # Incident {incidentId}
            Service: {service}
            Summary: {summary}

            ## Findings
            - {code} [{severity}] evidence: {evidenceRefs joined by ", "}

            ## Evidence
            - {evidenceId} | {observedAt} | {source} | {kind} | {message}

            Calling the bridge repeatedly with the same logical input must produce equal normalized
            outputs and identical reportMarkdown UTF-8 bytes. Do not mutate the supplied input Map
            or its nested lists/maps. No network, file, process, LLM, sleep, or external side effect
            belongs in the bridge. A deterministic core does not itself prove an Agent Runtime.
            The surrounding candidate remains Java 21 with Flower 0.1.3; the bridge is product-specific
            pure code and adds no Action merely to satisfy a dependency list.
            """.getBytes(StandardCharsets.UTF_8));
    private static final Material API_INDEX = material("api-signature-index", "application/json", json(Map.ofEntries(
            Map.entry("schemaVersion", "factory.maintenance-investigation-api-index.v1"),
            Map.entry("contractId", CONTRACT_ID),
            Map.entry("contractVersion", CONTRACT_VERSION),
            Map.entry("bridgeScope", "product-core-characterization-only-not-agent-runtime-abi"),
            Map.entry("className", API_CLASS_NAME),
            Map.entry("methodName", API_METHOD_NAME),
            Map.entry("signature", "public static java.util.Map<String,Object> investigate(java.util.Map<String,Object> incident)"),
            Map.entry("javaVersion", "21"),
            Map.entry("flowerVersion", "0.1.3"),
            Map.entry("unknownFieldPolicy", "reject-at-input-and-output"),
            Map.entry("inputFields", Map.of("incidentId", "String", "service", "String",
                    "summary", "String", "observations", "List<Observation>")),
            Map.entry("observationFields", Map.of("evidenceId", "String", "source", "String",
                    "observedAt", "String:Instant", "kind", "String", "message", "String")),
            Map.entry("successOutputFields", Map.of("incidentId", "String", "service", "String", "summary", "String",
                    "evidence", "List<Observation>", "findings", "List<Finding>", "reportMarkdown", "String:LF")),
            Map.entry("findingFields", Map.of("code", "String", "severity", "String", "evidenceRefs", "List<String>")),
            Map.entry("errorOutputFields", Map.of("errorCode", List.of(INVALID_INCIDENT, EVIDENCE_ID_CONFLICT))))));
    private static final Material CASE_SUITE = material("case-suite", "application/json", json(Map.of(
            "schemaVersion", CASE_SUITE_SCHEMA,
            "contractId", CONTRACT_ID,
            "contractVersion", CONTRACT_VERSION,
            "cases", CASES.stream().map(AcceptanceCase::asMap).toList())));
    private static final Material REQUIREMENT_MATRIX = material("requirement-test-matrix", "application/json", json(Map.of(
            "schemaVersion", "factory.maintenance-investigation-requirement-matrix.v1",
            "contractId", CONTRACT_ID,
            "contractVersion", CONTRACT_VERSION,
            "requirements", List.of(
                    requirement("MI-INPUT", "Exact bounded input shape, scalar and timestamp validation",
                            "INVALID", "UNKNOWN_FIELD", "UNKNOWN_OBSERVATION_FIELD", "INVALID_TIMESTAMP",
                            "INVALID_SCALAR", "INVALID_BLANK", "OVERSIZED_IDENTIFIER", "TOO_MANY_OBSERVATIONS"),
                    requirement("MI-NORMALIZE", "Trim and normalize evidence without mutating input",
                            "NORMAL", "WHITESPACE_DUPLICATE", "ORDER_GROUPING"),
                    requirement("MI-DUPLICATE", "Collapse identical evidence; reject conflicting identifiers",
                            "WHITESPACE_DUPLICATE", "CONFLICT", "INVALID_PRECEDES_CONFLICT"),
                    requirement("MI-FINDINGS", "Exact case-insensitive classification, precedence and grouping",
                            "NORMAL", "CASE_INSENSITIVE", "ORDER_GROUPING"),
                    requirement("MI-GROUNDING", "Sorted unique references resolve to normalized evidence",
                            "NORMAL", "ORDER_GROUPING"),
                    requirement("MI-REPORT", "Exact report layout and LF bytes, including empty result",
                            "NORMAL", "EMPTY", "ORDER_GROUPING"),
                    requirement("MI-DETERMINISM", "Repeated calls produce equal outputs and identical report bytes",
                            "DETERMINISM")))));
    private static final Material CONTRACT = material("product-contract", "application/json", json(Map.ofEntries(
            Map.entry("schemaVersion", "factory.product-contract.v1"),
            Map.entry("contractId", CONTRACT_ID),
            Map.entry("contractVersion", CONTRACT_VERSION),
            Map.entry("artifactType", "AGENT_PACK"),
            Map.entry("productLineId", "agent-pack"),
            Map.entry("requiredGateProfile", GATE_PROFILE),
            Map.entry("canonicalJsonAlgorithmId", CANONICAL_JSON_ALGORITHM_ID),
            Map.entry("scope", "maintenance-investigation-core-characterization"),
            Map.entry("requirements", lockMap(REQUIREMENTS.lock())),
            Map.entry("apiSignatureIndex", lockMap(API_INDEX.lock())),
            Map.entry("requirementTestMatrix", lockMap(REQUIREMENT_MATRIX.lock())),
            Map.entry("caseSuite", lockMap(CASE_SUITE.lock())),
            Map.entry("maxInputBytes", MAX_INPUT_BYTES),
            Map.entry("maxOutputBytes", MAX_OUTPUT_BYTES),
            Map.entry("maxObservations", MAX_OBSERVATIONS),
            Map.entry("javaVersion", "21"),
            Map.entry("flowerVersion", "0.1.3"))));

    private MaintenanceInvestigationProductContract() {}

    public static CertificationArtifactLock lock() { return CONTRACT.lock(); }
    public static byte[] canonicalBytes() { return CONTRACT.bytes(); }
    public static Artifact artifact(TenantId tenantId) { return CONTRACT.artifact(tenantId); }
    public static CertificationArtifactLock requirementsLock() { return REQUIREMENTS.lock(); }
    public static byte[] requirementsBytes() { return REQUIREMENTS.bytes(); }
    public static CertificationArtifactLock apiSignatureIndexLock() { return API_INDEX.lock(); }
    public static byte[] apiSignatureIndexBytes() { return API_INDEX.bytes(); }
    public static CertificationArtifactLock requirementTestMatrixLock() { return REQUIREMENT_MATRIX.lock(); }
    public static byte[] requirementTestMatrixBytes() { return REQUIREMENT_MATRIX.bytes(); }
    public static CertificationArtifactLock caseSuiteLock() { return CASE_SUITE.lock(); }
    public static byte[] caseSuiteBytes() { return CASE_SUITE.bytes(); }
    public static List<AcceptanceCase> cases() { return CASES; }

    /** Pure artifact materialization; the caller owns any governed storage operation. */
    public static List<Artifact> artifacts(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return List.of(REQUIREMENTS, API_INDEX, REQUIREMENT_MATRIX, CASE_SUITE, CONTRACT).stream()
                .map(material -> material.artifact(tenantId)).toList();
    }

    /** Deeply immutable golden case; repeatInvocations never changes the expected result. */
    public record AcceptanceCase(
            String caseId, Map<String, Object> input, Map<String, Object> expectedOutput,
            int repeatInvocations) {
        public AcceptanceCase {
            if (caseId == null || !caseId.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw new IllegalArgumentException("caseId must be a bounded uppercase stable id");
            }
            input = freezeMap(Objects.requireNonNull(input, "input"));
            expectedOutput = freezeMap(Objects.requireNonNull(expectedOutput, "expectedOutput"));
            if (repeatInvocations < 1 || repeatInvocations > 3) {
                throw new IllegalArgumentException("repeatInvocations must be between one and three");
            }
        }

        public byte[] inputBytes() { return json(input); }
        public byte[] expectedOutputBytes() { return json(expectedOutput); }

        private Map<String, Object> asMap() {
            return Map.of("caseId", caseId, "input", input, "expectedOutput", expectedOutput,
                    "repeatInvocations", repeatInvocations);
        }
    }

    private static List<AcceptanceCase> goldenCases() {
        var cases = new ArrayList<AcceptanceCase>();
        var ev1 = evidence("EV-1", "sanitized-log", "2026-09-06T01:02:03Z", "LOG", "upstream timeout");
        var ev2 = evidence("EV-2", "sanitized-metric", "2026-09-06T01:03:00Z", "METRIC", "error rate elevated");
        var normal = incident("INC-1001", "checkout-api", "Intermittent checkout failures", List.of(ev2, ev1));
        var normalOutput = success("INC-1001", "checkout-api", "Intermittent checkout failures",
                List.of(ev1, ev2), List.of(
                        finding("ERROR_RATE_ELEVATED", "HIGH", "EV-2"),
                        finding("SERVICE_TIMEOUT", "HIGH", "EV-1")), """
                # Incident INC-1001
                Service: checkout-api
                Summary: Intermittent checkout failures

                ## Findings
                - ERROR_RATE_ELEVATED [HIGH] evidence: EV-2
                - SERVICE_TIMEOUT [HIGH] evidence: EV-1

                ## Evidence
                - EV-1 | 2026-09-06T01:02:03Z | sanitized-log | LOG | upstream timeout
                - EV-2 | 2026-09-06T01:03:00Z | sanitized-metric | METRIC | error rate elevated
                """);
        cases.add(new AcceptanceCase("NORMAL", normal, normalOutput, 1));

        var normalized = evidence("EV-1", "log", "2026-09-06T00:00:00Z", "LOG", "ordinary observation");
        cases.add(new AcceptanceCase("WHITESPACE_DUPLICATE",
                incident(" INC-2 ", " api ", " Check observation ", List.of(
                        evidence(" EV-1 ", " log ", " 2026-09-06T00:00:00+00:00 ", " LOG ", " ordinary observation "),
                        normalized)),
                success("INC-2", "api", "Check observation", List.of(normalized),
                        List.of(finding("OBSERVATION_REQUIRES_REVIEW", "MEDIUM", "EV-1")), """
                        # Incident INC-2
                        Service: api
                        Summary: Check observation

                        ## Findings
                        - OBSERVATION_REQUIRES_REVIEW [MEDIUM] evidence: EV-1

                        ## Evidence
                        - EV-1 | 2026-09-06T00:00:00Z | log | LOG | ordinary observation
                        """), 1));
        var conflicting = evidence("EV-1", "log", "2026-09-06T00:00:00Z", "LOG", "different observation");
        cases.add(new AcceptanceCase("CONFLICT", incident("INC-3", "api", "Conflict",
                List.of(normalized, conflicting)), error(EVIDENCE_ID_CONFLICT), 1));
        cases.add(new AcceptanceCase("INVALID", Map.of("incidentId", "INC-4", "summary", "Missing service",
                "observations", List.of()), error(INVALID_INCIDENT), 1));

        var caseA = evidence("A", "log", "2026-09-06T00:00:00Z", "LOG", "TIMEOUT and 5XX");
        var caseB = evidence("B", "log", "2026-09-06T00:00:01Z", "LOG", "ErRoR RaTe increased");
        var caseC = evidence("C", "log", "2026-09-06T00:00:02Z", "LOG", "5XX responses");
        cases.add(new AcceptanceCase("CASE_INSENSITIVE",
                incident("INC-5", "api", "Case and precedence", List.of(caseC, caseB, caseA)),
                success("INC-5", "api", "Case and precedence", List.of(caseA, caseB, caseC), List.of(
                        finding("ERROR_RATE_ELEVATED", "HIGH", "B", "C"),
                        finding("SERVICE_TIMEOUT", "HIGH", "A")), """
                        # Incident INC-5
                        Service: api
                        Summary: Case and precedence

                        ## Findings
                        - ERROR_RATE_ELEVATED [HIGH] evidence: B, C
                        - SERVICE_TIMEOUT [HIGH] evidence: A

                        ## Evidence
                        - A | 2026-09-06T00:00:00Z | log | LOG | TIMEOUT and 5XX
                        - B | 2026-09-06T00:00:01Z | log | LOG | ErRoR RaTe increased
                        - C | 2026-09-06T00:00:02Z | log | LOG | 5XX responses
                        """), 1));

        var groupZ = evidence("Z", "log", "2026-09-06T00:00:00Z", "LOG", "timeout early");
        var groupB = evidence("B", "note", "2026-09-06T00:00:01Z", "NOTE", "investigate next");
        var groupA = evidence("A", "log", "2026-09-06T00:00:01Z", "LOG", "timeout later");
        var groupedInput = incident("INC-6", "api", "Ordering and grouping", List.of(groupB, groupA, groupZ, groupA));
        var groupedOutput = success("INC-6", "api", "Ordering and grouping", List.of(groupZ, groupA, groupB), List.of(
                finding("OBSERVATION_REQUIRES_REVIEW", "MEDIUM", "B"),
                finding("SERVICE_TIMEOUT", "HIGH", "A", "Z")), """
                # Incident INC-6
                Service: api
                Summary: Ordering and grouping

                ## Findings
                - OBSERVATION_REQUIRES_REVIEW [MEDIUM] evidence: B
                - SERVICE_TIMEOUT [HIGH] evidence: A, Z

                ## Evidence
                - Z | 2026-09-06T00:00:00Z | log | LOG | timeout early
                - A | 2026-09-06T00:00:01Z | log | LOG | timeout later
                - B | 2026-09-06T00:00:01Z | note | NOTE | investigate next
                """);
        cases.add(new AcceptanceCase("ORDER_GROUPING", groupedInput, groupedOutput, 1));
        cases.add(new AcceptanceCase("EMPTY", incident("INC-7", "api", "No observations", List.of()),
                success("INC-7", "api", "No observations", List.of(), List.of(), """
                        # Incident INC-7
                        Service: api
                        Summary: No observations

                        ## Findings
                        - None

                        ## Evidence
                        - None
                        """), 1));
        cases.add(new AcceptanceCase("DETERMINISM", groupedInput, groupedOutput, 3));

        var unknown = new LinkedHashMap<>(normal);
        unknown.put("unrequested", "value");
        cases.add(new AcceptanceCase("UNKNOWN_FIELD", unknown, error(INVALID_INCIDENT), 1));
        var unknownObservation = new LinkedHashMap<>(normalized);
        unknownObservation.put("unrequested", "value");
        cases.add(new AcceptanceCase("UNKNOWN_OBSERVATION_FIELD",
                incident("INC-8", "api", "Unknown observation field", List.of(unknownObservation)),
                error(INVALID_INCIDENT), 1));
        var badTimestamp = evidence("EV-1", "log", "not-an-instant", "LOG", "timeout");
        cases.add(new AcceptanceCase("INVALID_TIMESTAMP", incident("INC-9", "api", "Invalid time",
                List.of(badTimestamp)), error(INVALID_INCIDENT), 1));
        cases.add(new AcceptanceCase("INVALID_SCALAR", Map.of("incidentId", 42, "service", "api",
                "summary", "Invalid scalar", "observations", List.of()), error(INVALID_INCIDENT), 1));
        cases.add(new AcceptanceCase("INVALID_BLANK", incident("INC-10", "   ", "Blank service", List.of()),
                error(INVALID_INCIDENT), 1));
        cases.add(new AcceptanceCase("OVERSIZED_IDENTIFIER", incident("I".repeat(MAX_IDENTIFIER_LENGTH + 1),
                "api", "Bounded identifier", List.of()), error(INVALID_INCIDENT), 1));
        cases.add(new AcceptanceCase("TOO_MANY_OBSERVATIONS", incident("INC-11", "api", "Bounded evidence",
                Collections.nCopies(MAX_OBSERVATIONS + 1, normalized)), error(INVALID_INCIDENT), 1));
        cases.add(new AcceptanceCase("INVALID_PRECEDES_CONFLICT", incident("INC-12", "api", "Error precedence",
                List.of(normalized, conflicting, badTimestamp)), error(INVALID_INCIDENT), 1));
        return List.copyOf(cases);
    }

    private static Map<String, Object> incident(String id, String service, String summary, List<?> observations) {
        return Map.of("incidentId", id, "service", service, "summary", summary, "observations", observations);
    }

    private static Map<String, Object> evidence(String id, String source, String time, String kind, String message) {
        return Map.of("evidenceId", id, "source", source, "observedAt", time, "kind", kind, "message", message);
    }

    private static Map<String, Object> finding(String code, String severity, String... refs) {
        return Map.of("code", code, "severity", severity, "evidenceRefs", List.of(refs));
    }

    private static Map<String, Object> success(String id, String service, String summary,
            List<?> evidence, List<?> findings, String report) {
        return Map.of("incidentId", id, "service", service, "summary", summary, "evidence", evidence,
                "findings", findings, "reportMarkdown", report);
    }

    private static Map<String, Object> error(String code) { return Map.of("errorCode", code); }

    private static Map<String, Object> requirement(String id, String description, String... caseIds) {
        return Map.of("requirementId", id, "description", description, "caseIds", List.of(caseIds));
    }

    private static Map<String, Object> lockMap(CertificationArtifactLock lock) {
        return Map.of("reference", lock.reference().value(), "sha256", lock.hash().sha256());
    }

    private static Material material(String kind, String mediaType, byte[] bytes) {
        ContentHash hash;
        try {
            hash = new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
        return new Material(new CertificationArtifactLock(new ArtifactReference(
                "factory-product-contract/maintenance-investigation/1.0.0/" + kind + "/sha256/" + hash.sha256()),
                hash), mediaType, bytes);
    }

    private record Material(CertificationArtifactLock lock, String mediaType, byte[] content) {
        private Material { content = content.clone(); }
        private byte[] bytes() { return content.clone(); }
        private Artifact artifact(TenantId tenantId) {
            return new Artifact(Objects.requireNonNull(tenantId, "tenantId"), lock.reference(), lock.hash(), mediaType, content);
        }
    }

    private static Map<String, Object> freezeMap(Map<String, ?> map) {
        var copy = new TreeMap<String, Object>();
        map.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "JSON key"), freeze(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Integer) {
            return value;
        }
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(list.stream().map(MaintenanceInvestigationProductContract::freeze).toList());
        }
        if (value instanceof Map<?, ?> map) {
            var copy = new TreeMap<String, Object>();
            map.forEach((key, element) -> {
                if (!(key instanceof String text)) throw new IllegalArgumentException("JSON keys must be strings");
                copy.put(text, freeze(element));
            });
            return Collections.unmodifiableMap(copy);
        }
        throw new IllegalArgumentException("golden cases support only bounded JSON scalar/list/map values");
    }

    /** This encoder is private to the fixed contract; it is not a new general-purpose serialization SPI. */
    private static byte[] json(Object value) {
        var output = new StringBuilder();
        appendJson(output, value);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendJson(StringBuilder output, Object value) {
        if (value == null) { output.append("null"); return; }
        if (value instanceof String text) {
            output.append('"');
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                switch (character) {
                    case '"' -> output.append("\\\"");
                    case '\\' -> output.append("\\\\");
                    case '\n' -> output.append("\\n");
                    case '\r' -> output.append("\\r");
                    case '\t' -> output.append("\\t");
                    default -> {
                        if (character < 0x20 || Character.isSurrogate(character)) {
                            output.append("\\u");
                            String hex = Integer.toHexString(character);
                            output.append("0".repeat(4 - hex.length())).append(hex);
                        } else output.append(character);
                    }
                }
            }
            output.append('"');
        } else if (value instanceof Integer || value instanceof Boolean) {
            output.append(value);
        } else if (value instanceof List<?> list) {
            output.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) output.append(',');
                appendJson(output, list.get(index));
            }
            output.append(']');
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put((String) key, item));
            boolean first = true;
            for (var entry : sorted.entrySet()) {
                if (!first) output.append(',');
                first = false;
                appendJson(output, entry.getKey());
                output.append(':');
                appendJson(output, entry.getValue());
            }
            output.append('}');
        } else throw new IllegalArgumentException("unsupported fixed-contract JSON value");
    }
}
