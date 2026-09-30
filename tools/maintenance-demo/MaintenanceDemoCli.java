import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.pack.maintenance.InvestigationAcceptanceApi;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** Local smoke-test adapter, NOT part of the certified source or a Factory production entry point. */
public final class MaintenanceDemoCli {
    static final int MAX_INPUT_BYTES = 256 * 1024;
    static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final Set<String> PRODUCT_ERRORS = Set.of("INVALID_INCIDENT", "EVIDENCE_ID_CONFLICT");
    private static final Set<String> RESULT_FIELDS = Set.of(
            "incidentId", "service", "summary", "evidence", "findings", "reportMarkdown");

    private MaintenanceDemoCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out));
    }

    static int run(String[] args, InputStream input, OutputStream output) {
        if (args.length != 0) return error(output, "CLI_ARGUMENTS_NOT_SUPPORTED", 2);
        byte[] bytes;
        try {
            bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
        } catch (IOException failure) {
            return error(output, "CLI_INPUT_READ_FAILED", 4);
        }
        if (bytes.length > MAX_INPUT_BYTES) return error(output, "CLI_INPUT_TOO_LARGE", 2);
        if (bytes.length == 0) return error(output, "CLI_INVALID_JSON", 2);

        ObjectMapper mapper = mapper();
        Map<String, Object> incident;
        try {
            String json = utf8(bytes);
            if (json.startsWith("\uFEFF")) json = json.substring(1); // UTF-8 BOM from ordinary editors is allowed.
            incident = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            if (incident == null) return error(output, "CLI_INVALID_JSON", 2);
        } catch (IOException | RuntimeException failure) {
            // Do not echo parser exceptions: they can contain the user's incident text.
            return error(output, "CLI_INVALID_JSON", 2);
        }

        try {
            Map<String, Object> result = InvestigationAcceptanceApi.investigate(incident);
            if (result == null) return error(output, "CLI_RESULT_INVALID", 4);
            if (result.containsKey("errorCode")) {
                Object code = result.get("errorCode");
                if (result.size() != 1 || !(code instanceof String value) || !PRODUCT_ERRORS.contains(value)) {
                    return error(output, "CLI_RESULT_INVALID", 4);
                }
                return error(output, (String) code, 3);
            }
            if (!result.keySet().equals(RESULT_FIELDS) || !(result.get("reportMarkdown") instanceof String)) {
                return error(output, "CLI_RESULT_INVALID", 4);
            }
            byte[] response = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
            if (response.length + 1 > MAX_OUTPUT_BYTES) return error(output, "CLI_OUTPUT_TOO_LARGE", 4);
            output.write(response);
            output.write('\n');
            output.flush();
            return 0;
        } catch (IOException | RuntimeException failure) {
            // No stack trace, generated path, environment or incident content goes to stderr.
            return error(output, "CLI_EXECUTION_FAILED", 4);
        }
    }

    static ObjectMapper mapper() {
        JsonFactory factory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(16).maxStringLength(16 * 1024).maxNumberLength(128).build()).build();
        return new ObjectMapper(factory).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    private static String utf8(byte[] input) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString();
    }

    private static int error(OutputStream output, String code, int exitCode) {
        try {
            output.write(("{\"errorCode\":\"" + code + "\"}\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            return exitCode;
        } catch (IOException failure) {
            return 4;
        }
    }
}
