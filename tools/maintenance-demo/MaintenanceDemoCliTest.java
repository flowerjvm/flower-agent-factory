import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Standalone adapter regression tests. Execute only beside the real product in the isolated container. */
public final class MaintenanceDemoCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CANARY = "PRIVATE_INCIDENT_MUST_NOT_APPEAR_85c96e";
    private static final String EMPTY_INCIDENT =
            "{\"incidentId\":\"INC-TEST\",\"service\":\"api\",\"summary\":\"Check\",\"observations\":[]}";
    private static int adapterTests;
    private static int factoryCases;
    private static int factoryInvocations;

    private MaintenanceDemoCliTest() {}

    public static void main(String[] args) {
        try {
            require(args.length <= 1, "test harness expects zero or one fixture path");
            adapterTests();
            replayFactorySuite(Path.of(args.length == 0 ? "/fixtures/case-suite.json" : args[0]));
            System.out.println("PASS SUMMARY adapterTests=" + adapterTests + " factoryCases=" + factoryCases
                    + " factoryInvocations=" + factoryInvocations);
        } catch (Throwable failure) {
            // Never print user input, fixture values, exception messages or stack traces.
            System.err.println("FAIL TEST_SUITE cause=" + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static void adapterTests() throws Exception {
        check("normal_uppercase_unicode_ordering", MaintenanceDemoCliTest::normalUppercaseUnicodeOrdering);
        check("empty_observations_success", () -> require(invoke(EMPTY_INCIDENT).code() == 0, "empty success"));
        check("missing_domain_field", () -> error("{\"incidentId\":\"INC-X\",\"observations\":[]}",
                3, "INVALID_INCIDENT"));
        check("duplicate_evidence_conflict", () -> {
            String first = observation("EV-1", "2026-09-08T00:00:00Z", "TIMEOUT");
            String second = observation("EV-1", "2026-09-08T00:00:00Z", "different observation");
            error(incident(first + "," + second), 3, "EVIDENCE_ID_CONFLICT");
        });
        check("null_root", () -> error("null", 2, "CLI_INVALID_JSON"));
        check("array_root", () -> error("[]", 2, "CLI_INVALID_JSON"));
        check("string_root", () -> error("\"" + CANARY + "\"", 2, "CLI_INVALID_JSON"));
        check("boolean_root", () -> error("true", 2, "CLI_INVALID_JSON"));
        check("number_root", () -> error("42", 2, "CLI_INVALID_JSON"));
        check("duplicate_top_level_json_key", () -> error(
                EMPTY_INCIDENT.replace("\"incidentId\":\"INC-TEST\"",
                        "\"incidentId\":\"INC-TEST\",\"incidentId\":\"INC-TEST\""),
                2, "CLI_INVALID_JSON"));
        check("duplicate_nested_json_key", () -> error(incident(
                observation("EV-1", "2026-09-08T00:00:00Z", "TIMEOUT")
                        .replace("\"evidenceId\":\"EV-1\"",
                                "\"evidenceId\":\"EV-1\",\"evidenceId\":\"EV-1\"")),
                2, "CLI_INVALID_JSON"));
        check("trailing_json_object", () -> error(EMPTY_INCIDENT + " {}", 2, "CLI_INVALID_JSON"));
        check("trailing_json_scalar", () -> error(EMPTY_INCIDENT + " 1", 2, "CLI_INVALID_JSON"));
        check("malformed_json", () -> error("{\"incidentId\":\"" + CANARY + "\",}",
                2, "CLI_INVALID_JSON"));
        check("empty_input", () -> error("", 2, "CLI_INVALID_JSON"));
        check("whitespace_only_input", () -> error(" \r\n\t", 2, "CLI_INVALID_JSON"));
        check("utf8_bom_accepted", () -> {
            Result plain = invoke(EMPTY_INCIDENT);
            Result bom = invoke("\uFEFF" + EMPTY_INCIDENT);
            require(bom.code() == 0, "BOM accepted");
            require(tree(plain).equals(tree(bom)), "BOM result equivalence");
        });
        check("malformed_utf8_rejected", () -> {
            byte[] prefix = ("{\"incidentId\":\"" + CANARY).getBytes(StandardCharsets.UTF_8);
            byte[] malformed = Arrays.copyOf(prefix, prefix.length + 2);
            malformed[prefix.length] = (byte) 0xc3;
            malformed[prefix.length + 1] = (byte) 0x28;
            error(invoke(malformed), 2, "CLI_INVALID_JSON");
        });
        check("input_byte_limit_inclusive", () -> {
            int padding = MaintenanceDemoCli.MAX_INPUT_BYTES - EMPTY_INCIDENT.getBytes(StandardCharsets.UTF_8).length;
            require(invoke(EMPTY_INCIDENT + " ".repeat(padding)).code() == 0, "input exactly at limit");
        });
        check("oversized_input_rejected", () -> {
            byte[] oversized = new byte[MaintenanceDemoCli.MAX_INPUT_BYTES + 1];
            Arrays.fill(oversized, (byte) ' ');
            error(invoke(oversized), 2, "CLI_INPUT_TOO_LARGE");
        });
        check("overdepth_json_rejected", () -> error("{\"nested\":" + "[".repeat(17) + "0"
                + "]".repeat(17) + "}", 2, "CLI_INVALID_JSON"));
        check("oversized_json_string_rejected", () -> error("{\"incidentId\":\"" + "x".repeat(20_000)
                + "\"}", 2, "CLI_INVALID_JSON"));
        check("oversized_json_number_rejected", () -> error("{\"incidentId\":" + "1".repeat(129)
                + "}", 2, "CLI_INVALID_JSON"));
        check("parser_errors_do_not_echo_input", () -> error(
                "{\"incidentId\":\"" + CANARY + "\",\"summary\":", 2, "CLI_INVALID_JSON"));
        check("domain_errors_do_not_echo_input", () -> error(
                "{\"incidentId\":\"" + CANARY + "\",\"observations\":[]}", 3, "INVALID_INCIDENT"));
        check("unexpected_argument_rejected", () -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int code = MaintenanceDemoCli.run(new String[] { CANARY },
                    new ByteArrayInputStream(EMPTY_INCIDENT.getBytes(StandardCharsets.UTF_8)), output);
            error(new Result(code, output.toByteArray()), 2, "CLI_ARGUMENTS_NOT_SUPPORTED");
        });
        check("input_read_io_failure", () -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            InputStream broken = new InputStream() {
                @Override public int read() throws IOException { throw new IOException(CANARY); }
            };
            int code = MaintenanceDemoCli.run(new String[0], broken, output);
            error(new Result(code, output.toByteArray()), 4, "CLI_INPUT_READ_FAILED");
        });
        check("success_output_write_io_failure", () -> {
            int code = MaintenanceDemoCli.run(new String[0],
                    new ByteArrayInputStream(EMPTY_INCIDENT.getBytes(StandardCharsets.UTF_8)), brokenOutput());
            require(code == 4, "success write failure cannot return success");
        });
        check("error_output_write_io_failure", () -> {
            int code = MaintenanceDemoCli.run(new String[0], new ByteArrayInputStream(new byte[0]), brokenOutput());
            require(code == 4, "error write failure returns I/O error");
        });
        check("output_flush_io_failure", () -> {
            OutputStream brokenFlush = new ByteArrayOutputStream() {
                @Override public void flush() throws IOException { throw new IOException(CANARY); }
            };
            int code = MaintenanceDemoCli.run(new String[0],
                    new ByteArrayInputStream(EMPTY_INCIDENT.getBytes(StandardCharsets.UTF_8)), brokenFlush);
            require(code == 4, "flush failure cannot return success");
        });
    }

    private static void normalUppercaseUnicodeOrdering() throws Exception {
        String input = incident(observation("B", "2026-09-08T00:00:01Z", "5XX 오류 증가") + ","
                + observation("A", "2026-09-08T00:00:01Z", "TIMEOUT and 5XX") + ","
                + observation("Z", "2026-09-08T00:00:00Z", "TIMEOUT 대기 초과 🚢"))
                .replace("\"summary\":\"Check\"", "\"summary\":\"결제 장애 조사 🚢\"");
        Result actual = invoke(input);
        require(actual.code() == 0, "normal success");
        String report = "# Incident INC-TEST\nService: api\nSummary: 결제 장애 조사 🚢\n\n## Findings\n"
                + "- ERROR_RATE_ELEVATED [HIGH] evidence: B\n- SERVICE_TIMEOUT [HIGH] evidence: A, Z\n"
                + "\n## Evidence\n"
                + "- Z | 2026-09-08T00:00:00Z | log | LOG | TIMEOUT 대기 초과 🚢\n"
                + "- A | 2026-09-08T00:00:01Z | log | LOG | TIMEOUT and 5XX\n"
                + "- B | 2026-09-08T00:00:01Z | log | LOG | 5XX 오류 증가\n";
        Map<String, Object> expected = Map.of(
                "incidentId", "INC-TEST", "service", "api", "summary", "결제 장애 조사 🚢",
                "evidence", List.of(
                        JSON.readTree(observation("Z", "2026-09-08T00:00:00Z", "TIMEOUT 대기 초과 🚢")),
                        JSON.readTree(observation("A", "2026-09-08T00:00:01Z", "TIMEOUT and 5XX")),
                        JSON.readTree(observation("B", "2026-09-08T00:00:01Z", "5XX 오류 증가"))),
                "findings", List.of(
                        Map.of("code", "ERROR_RATE_ELEVATED", "severity", "HIGH", "evidenceRefs", List.of("B")),
                        Map.of("code", "SERVICE_TIMEOUT", "severity", "HIGH", "evidenceRefs", List.of("A", "Z"))),
                "reportMarkdown", report);
        require(tree(actual).equals(JSON.valueToTree(expected)), "normal complete structural output");
        require(actual.bytes()[actual.bytes().length - 1] == '\n', "newline terminated JSON");
        require(new String(actual.bytes(), StandardCharsets.UTF_8).contains("결제 장애 조사"), "UTF-8 preserved");
    }

    private static void replayFactorySuite(Path path) throws Exception {
        JsonNode suite;
        try (InputStream fixture = Files.newInputStream(path)) {
            byte[] bytes = fixture.readNBytes(1024 * 1024 + 1);
            require(bytes.length <= 1024 * 1024, "bounded fixture");
            suite = JSON.readTree(bytes);
        }
        require(suite.isObject(), "fixture root");
        require("factory.maintenance-investigation-case-suite.v1".equals(suite.path("schemaVersion").asText()),
                "fixture schema");
        require("maintenance-investigation-pack".equals(suite.path("contractId").asText()), "fixture contract");
        require("1.0.0".equals(suite.path("contractVersion").asText()), "fixture contract version");
        JsonNode cases = suite.path("cases");
        require(cases.isArray() && cases.size() == 16, "all sixteen original cases required");
        Set<String> seen = new HashSet<>();
        for (JsonNode test : cases) {
            String caseId = test.path("caseId").asText();
            require(caseId.matches("[A-Z_]{1,64}") && seen.add(caseId), "bounded unique case ID");
            require(test.path("input").isObject() && test.path("expectedOutput").isObject(), "fixture case shape");
            require(test.path("repeatInvocations").isIntegralNumber(), "fixture invocation type");
            int repeats = test.path("repeatInvocations").asInt();
            require(repeats >= 1 && repeats <= 3, "fixture invocation bounds");
            JsonNode expected = test.get("expectedOutput");
            byte[] input = JSON.writeValueAsBytes(test.get("input"));
            byte[] firstOutput = null;
            try {
                for (int iteration = 0; iteration < repeats; iteration++) {
                    Result actual = invoke(input);
                    require(actual.code() == (expected.has("errorCode") ? 3 : 0), "fixture CLI exit code");
                    require(tree(actual).equals(expected), "fixture immutable expected output");
                    if (firstOutput != null) require(Arrays.equals(firstOutput, actual.bytes()), "repeat byte determinism");
                    firstOutput = actual.bytes();
                    factoryInvocations++;
                }
            } catch (Throwable failure) {
                System.err.println("FAIL FACTORY_" + caseId);
                throw failure;
            }
            factoryCases++;
            System.out.println("PASS FACTORY_" + caseId + " invocations=" + repeats);
        }
        require(seen.equals(Set.of("NORMAL", "WHITESPACE_DUPLICATE", "CONFLICT", "INVALID", "CASE_INSENSITIVE",
                "ORDER_GROUPING", "EMPTY", "DETERMINISM", "UNKNOWN_FIELD", "UNKNOWN_OBSERVATION_FIELD",
                "INVALID_TIMESTAMP", "INVALID_SCALAR", "INVALID_BLANK", "OVERSIZED_IDENTIFIER",
                "TOO_MANY_OBSERVATIONS", "INVALID_PRECEDES_CONFLICT")), "all original case identities");
        require(factoryCases == 16 && factoryInvocations == 18, "complete factory suite invocation count");
    }

    private static void check(String name, CheckedTest test) throws Exception {
        try {
            test.run();
            adapterTests++;
            System.out.println("PASS ADAPTER_" + name);
        } catch (Throwable failure) {
            System.err.println("FAIL ADAPTER_" + name);
            throw failure;
        }
    }

    private static void error(String input, int code, String expectedError) throws Exception {
        error(invoke(input), code, expectedError);
    }

    private static void error(Result actual, int code, String expectedError) throws Exception {
        require(actual.code() == code, "expected nonzero exit code");
        require(actual.bytes().length <= 128, "bounded public error");
        require(tree(actual).equals(JSON.valueToTree(Map.of("errorCode", expectedError))), "exact public error");
        require(!new String(actual.bytes(), StandardCharsets.UTF_8).contains(CANARY), "input confidentiality");
    }

    private static Result invoke(String input) {
        return invoke(input.getBytes(StandardCharsets.UTF_8));
    }

    private static Result invoke(byte[] input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        return new Result(MaintenanceDemoCli.run(new String[0], new ByteArrayInputStream(input), output),
                output.toByteArray());
    }

    private static JsonNode tree(Result result) throws IOException {
        require(result.bytes().length != 0, "JSON output present");
        return JSON.readTree(result.bytes());
    }

    private static OutputStream brokenOutput() {
        return new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException(CANARY); }
        };
    }

    private static String incident(String observations) {
        return EMPTY_INCIDENT.replace("\"observations\":[]", "\"observations\":[" + observations + "]");
    }

    private static String observation(String id, String time, String message) throws IOException {
        return JSON.writeValueAsString(Map.of("evidenceId", id, "source", "log", "observedAt", time,
                "kind", "LOG", "message", message));
    }

    private static void require(boolean condition, String safeReason) {
        if (!condition) throw new AssertionError(safeReason);
    }

    @FunctionalInterface
    private interface CheckedTest { void run() throws Exception; }
    private record Result(int code, byte[] bytes) {}
}
