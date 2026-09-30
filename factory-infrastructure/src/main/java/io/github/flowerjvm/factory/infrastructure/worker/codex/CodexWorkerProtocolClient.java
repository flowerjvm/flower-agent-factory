package io.github.flowerjvm.factory.infrastructure.worker.codex;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.factory.contracts.worker.WorkerAttemptToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fixed-argv, bounded one-request process client for the local Codex runner. */
public final class CodexWorkerProtocolClient {
    static final String PROTOCOL_VERSION = "flower-codex-worker/1";
    private static final int MAX_REQUEST_BYTES = 192 * 1024;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_STDERR_BYTES = 32 * 1024;
    private static final Set<String> COMMON_SUCCESS = Set.of(
            "protocolVersion", "command", "ok", "observation", "snapshot");
    private static final Set<String> SNAPSHOT_FIELDS = Set.of(
            "tenantId", "workOrderId", "workerRunId", "operationId", "status", "stableCode",
            "effectAcceptedAt", "effectTerminalAt", "snapshotProof", "externalSessionRef",
            "completionEnvelope");
    private static final Set<String> COMPLETION_FIELDS = Set.of(
            "schemaVersion", "eventId", "workOrderId", "workerRunId", "operationId", "status",
            "outputs", "transcript", "stableCode", "attemptProof");
    private static final Set<String> OUTPUT_FIELDS = Set.of(
            "relativePath", "sha256", "sizeBytes", "kind");
    private static final Set<String> TRANSCRIPT_FIELDS = Set.of(
            "relativePath", "sha256", "sizeBytes");

    private final CodexWorkerBinding binding;
    private final ObjectMapper mapper;

    public CodexWorkerProtocolClient(CodexWorkerBinding binding) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.mapper = new ObjectMapper();
        this.mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    ObjectMapper objectMapper() {
        return mapper;
    }

    public CapabilitiesReply capabilities() {
        ObjectNode request = base("capabilities");
        ObjectNode response = invoke("capabilities", request, Optional.empty());
        exactFields(response, Set.of(
                "protocolVersion", "command", "ok", "worker", "capabilities", "execution"));
        if (!"codex".equals(text(response, "worker", 32))) {
            throw protocolInvalid(false);
        }
        List<String> capabilities = textArray(response, "capabilities", 32, 128);
        ObjectNode execution = object(response.get("execution"));
        exactFields(execution, Set.of(
                "approvalPolicy", "commandNetworkEnabled", "credentialIsolation",
                "credentialIsolationEvidenceSha256", "permissionProfile", "sessionResumeRequired",
                "webSearchMode"));
        if (!"never".equals(text(execution, "approvalPolicy", 32))
                || booleanValue(execution, "commandNetworkEnabled")
                || !"COMMAND_SENTINEL_PROVEN".equals(text(execution, "credentialIsolation", 64))
                || !"factory-worker".equals(text(execution, "permissionProfile", 64))
                || booleanValue(execution, "sessionResumeRequired")
                || !"disabled".equals(text(execution, "webSearchMode", 32))) {
            throw protocolInvalid(false);
        }
        return new CapabilitiesReply(
                capabilities, lowerSha256(execution, "credentialIsolationEvidenceSha256"));
    }

    public OperationReply submit(ObjectNode request, WorkerAttemptToken token) {
        return operation("submit", request, token);
    }

    public OperationReply status(ObjectNode request, WorkerAttemptToken token) {
        return operation("status", request, token);
    }

    public OperationReply cancel(ObjectNode request, WorkerAttemptToken token) {
        return operation("cancel", request, token);
    }

    ObjectNode base(String command) {
        ObjectNode request = mapper.createObjectNode();
        request.put("protocolVersion", PROTOCOL_VERSION);
        request.put("command", command);
        return request;
    }

    private OperationReply operation(
            String command,
            ObjectNode request,
            WorkerAttemptToken token) {
        ObjectNode response = invoke(command, request, Optional.of(token));
        exactFields(response, COMMON_SUCCESS);
        String observation = text(response, "observation", 32);
        JsonNode snapshotNode = response.get("snapshot");
        if ("NOT_FOUND".equals(observation)) {
            if (snapshotNode == null || !snapshotNode.isNull()) {
                throw protocolInvalid(false);
            }
            return new OperationReply(Lookup.NOT_FOUND, Optional.empty());
        }
        if (!"FOUND".equals(observation) || snapshotNode == null || snapshotNode.isNull()) {
            throw protocolInvalid(false);
        }
        return new OperationReply(Lookup.FOUND, Optional.of(parseSnapshot(object(snapshotNode))));
    }

    private ProtocolSnapshot parseSnapshot(ObjectNode node) {
        exactFields(node, SNAPSHOT_FIELDS);
        Optional<RawCompletion> completion = Optional.empty();
        JsonNode completionNode = node.get("completionEnvelope");
        if (completionNode != null && !completionNode.isNull()) {
            completion = Optional.of(parseCompletion(object(completionNode)));
        }
        return new ProtocolSnapshot(
                text(node, "tenantId", 256),
                text(node, "workOrderId", 256),
                text(node, "workerRunId", 256),
                text(node, "operationId", 256),
                text(node, "status", 32),
                text(node, "stableCode", 128),
                instantText(node, "effectAcceptedAt"),
                optionalInstantText(node, "effectTerminalAt"),
                lowerSha256(node, "snapshotProof"),
                text(node, "externalSessionRef", 512),
                completion);
    }

    private RawCompletion parseCompletion(ObjectNode node) {
        exactFields(node, COMPLETION_FIELDS);
        var outputs = new ArrayList<RawOutput>();
        JsonNode outputNode = node.get("outputs");
        if (outputNode == null || !outputNode.isArray() || outputNode.size() > 4096) {
            throw protocolInvalid(false);
        }
        for (JsonNode value : outputNode) {
            ObjectNode output = object(value);
            exactFields(output, OUTPUT_FIELDS);
            long size = longValue(output, "sizeBytes", 0, 8L * 1024 * 1024);
            outputs.add(new RawOutput(
                    text(output, "relativePath", 1024),
                    lowerSha256(output, "sha256"),
                    size,
                    text(output, "kind", 16)));
        }
        Optional<RawTranscript> transcript = Optional.empty();
        JsonNode transcriptNode = node.get("transcript");
        if (transcriptNode != null && !transcriptNode.isNull()) {
            ObjectNode value = object(transcriptNode);
            exactFields(value, TRANSCRIPT_FIELDS);
            transcript = Optional.of(new RawTranscript(
                    text(value, "relativePath", 1024),
                    lowerSha256(value, "sha256"),
                    longValue(value, "sizeBytes", 0, 64 * 1024)));
        }
        return new RawCompletion(
                text(node, "schemaVersion", 128),
                text(node, "eventId", 128),
                text(node, "workOrderId", 256),
                text(node, "workerRunId", 256),
                text(node, "operationId", 256),
                text(node, "status", 32),
                List.copyOf(outputs), transcript,
                text(node, "stableCode", 128),
                lowerSha256(node, "attemptProof"));
    }

    private ObjectNode invoke(
            String expectedCommand,
            ObjectNode request,
            Optional<WorkerAttemptToken> token) {
        byte[] input;
        try {
            input = mapper.writeValueAsBytes(request);
        } catch (IOException exception) {
            throw new CodexWorkerProtocolException("CODING_WORKER_REQUEST_ENCODING_FAILED", false);
        }
        if (input.length > MAX_REQUEST_BYTES) {
            throw new CodexWorkerProtocolException("CODING_WORKER_REQUEST_TOO_LARGE", false);
        }
        ProcessBuilder builder = new ProcessBuilder(List.of(
                binding.nodeExecutable().toString(),
                binding.runnerEntrypoint().toString(),
                "--state-root", binding.stateRoot().toString(),
                "--workspace-base", binding.stateRoot().resolve("tenants").toString()));
        builder.redirectErrorStream(false);
        var environment = builder.environment();
        environment.clear();
        environment.putAll(binding.processEnvironment());
        environment.put("FACTORY_CODEX_HOME", binding.codexHome().toString());
        environment.put("FACTORY_CODEX_SOURCE_WORKSPACE_BASE", binding.workspaceBase().toString());
        binding.model().ifPresent(value -> environment.put("FACTORY_CODEX_MODEL", value));
        binding.codexPath().ifPresent(value -> environment.put("FACTORY_CODEX_PATH", value.toString()));
        token.ifPresent(value -> environment.put("FACTORY_WORKER_ATTEMPT_TOKEN", value.reveal()));

        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new CodexWorkerProtocolException("CODING_WORKER_PROCESS_START_FAILED", false);
        } finally {
            environment.remove("FACTORY_WORKER_ATTEMPT_TOKEN");
        }

        ExecutorService lanes = Executors.newFixedThreadPool(3);
        try {
            Future<Void> writer = lanes.submit(() -> {
                try (var output = process.getOutputStream()) {
                    output.write(input);
                    output.flush();
                    return null;
                }
            });
            Future<byte[]> stdout = lanes.submit(() -> readBounded(process.getInputStream(), MAX_RESPONSE_BYTES));
            Future<byte[]> stderr = lanes.submit(() -> readBounded(process.getErrorStream(), MAX_STDERR_BYTES));
            boolean exited;
            try {
                exited = process.waitFor(binding.processTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                terminate(process);
                throw new CodexWorkerProtocolException("CODING_WORKER_PROCESS_INTERRUPTED", true);
            }
            if (!exited) {
                terminate(process);
                throw new CodexWorkerProtocolException("CODING_WORKER_PROCESS_TIMEOUT", true);
            }
            await(writer, binding.processTimeout());
            byte[] output = await(stdout, binding.processTimeout());
            await(stderr, binding.processTimeout()); // Deliberately discarded and never attached to an exception.
            ObjectNode parsed = parseResponse(output, expectedCommand);
            boolean ok = booleanValue(parsed, "ok");
            if (!ok) {
                exactFields(parsed, Set.of("protocolVersion", "command", "ok", "error"));
                ObjectNode error = object(parsed.get("error"));
                exactFields(error, Set.of("code", "message", "retryable"));
                String code = text(error, "code", 128);
                text(error, "message", 256); // Validate then discard provider-controlled text.
                boolean uncertain = process.exitValue() == 0 || !isProvenNoEffect(code);
                throw new CodexWorkerProtocolException(code, uncertain);
            }
            if (process.exitValue() != 0) {
                throw protocolInvalid(true);
            }
            return parsed;
        } finally {
            lanes.shutdownNow();
        }
    }

    private ObjectNode parseResponse(byte[] bytes, String expectedCommand) {
        if (bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES) {
            throw protocolInvalid(true);
        }
        try {
            JsonNode parsed = mapper.readTree(bytes);
            ObjectNode object = object(parsed);
            enforceTreeQuota(object, 0, new int[] {0});
            if (!PROTOCOL_VERSION.equals(text(object, "protocolVersion", 64))
                    || !expectedCommand.equals(text(object, "command", 32))) {
                throw protocolInvalid(true);
            }
            return object;
        } catch (CodexWorkerProtocolException exception) {
            throw exception;
        } catch (IOException exception) {
            throw protocolInvalid(true);
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(handle -> {
            handle.destroy();
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        });
        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        try (input; var output = new ByteArrayOutputStream(Math.min(maximum, 8192))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maximum) {
                    throw new IOException("bounded process output exceeded");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static <T> T await(Future<T> future, Duration timeout) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CodexWorkerProtocolException("CODING_WORKER_PROCESS_INTERRUPTED", true);
        } catch (ExecutionException | TimeoutException exception) {
            throw protocolInvalid(true);
        }
    }

    private static boolean isProvenNoEffect(String code) {
        return code.startsWith("WORKER_REQUEST_")
                || code.startsWith("WORKER_INVALID_")
                || code.startsWith("WORKER_UNKNOWN_")
                || code.startsWith("WORKER_MISSING_")
                || code.equals("WORKER_PROTOCOL_VERSION_UNSUPPORTED")
                || code.equals("WORKER_SCHEMA_VERSION_UNSUPPORTED")
                || code.equals("WORKER_CREDENTIAL_ISOLATION_UNPROVEN")
                || code.equals("WORKER_PERMISSION_PROFILE_INVALID")
                || code.equals("WORKER_DEADLINE_ELAPSED")
                || code.startsWith("WORKER_INPUT_")
                || code.startsWith("WORKER_WORKSPACE_");
    }

    private static ObjectNode object(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            throw protocolInvalid(true);
        }
        return object;
    }

    private static void exactFields(ObjectNode object, Set<String> expected) {
        var actual = new HashSet<String>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw protocolInvalid(true);
        }
    }

    private static String text(ObjectNode object, String field, int maximum) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw protocolInvalid(true);
        }
        String text = value.textValue();
        if (text.isBlank() || text.length() > maximum || text.chars().anyMatch(Character::isISOControl)) {
            throw protocolInvalid(true);
        }
        return text;
    }

    private static String lowerSha256(ObjectNode object, String field) {
        String value = text(object, field, 64);
        if (!value.matches("[0-9a-f]{64}")) {
            throw protocolInvalid(true);
        }
        return value;
    }

    private static String instantText(ObjectNode object, String field) {
        String value = text(object, field, 64);
        try {
            Instant.parse(value);
            return value;
        } catch (RuntimeException invalid) {
            throw protocolInvalid(true);
        }
    }

    private static Optional<String> optionalInstantText(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null) {
            throw protocolInvalid(true);
        }
        if (value.isNull()) {
            return Optional.empty();
        }
        return Optional.of(instantText(object, field));
    }

    private static List<String> textArray(ObjectNode object, String field, int maximumEntries, int maximumText) {
        JsonNode value = object.get(field);
        if (!(value instanceof ArrayNode array) || array.size() > maximumEntries) {
            throw protocolInvalid(true);
        }
        var result = new ArrayList<String>();
        array.forEach(entry -> {
            if (!entry.isTextual()) {
                throw protocolInvalid(true);
            }
            String text = entry.textValue();
            if (text.isBlank() || text.length() > maximumText) {
                throw protocolInvalid(true);
            }
            result.add(text);
        });
        if (new HashSet<>(result).size() != result.size()) {
            throw protocolInvalid(true);
        }
        return List.copyOf(result);
    }

    private static boolean booleanValue(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isBoolean()) {
            throw protocolInvalid(true);
        }
        return value.booleanValue();
    }

    private static long longValue(ObjectNode object, String field, long minimum, long maximum) {
        JsonNode value = object.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw protocolInvalid(true);
        }
        long result = value.longValue();
        if (result < minimum || result > maximum) {
            throw protocolInvalid(true);
        }
        return result;
    }

    private static void enforceTreeQuota(JsonNode node, int depth, int[] count) {
        count[0]++;
        if (depth > 8 || count[0] > 8192) {
            throw protocolInvalid(true);
        }
        node.forEach(child -> enforceTreeQuota(child, depth + 1, count));
    }

    private static CodexWorkerProtocolException protocolInvalid(boolean uncertain) {
        return new CodexWorkerProtocolException("CODING_WORKER_PROTOCOL_INVALID", uncertain);
    }

    public record CapabilitiesReply(List<String> values, String isolationEvidenceSha256) {
        public CapabilitiesReply {
            values = List.copyOf(values);
            if (isolationEvidenceSha256 == null
                    || !isolationEvidenceSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("isolation evidence must be lowercase SHA-256");
            }
        }
    }

    public record OperationReply(Lookup lookup, Optional<ProtocolSnapshot> snapshot) {
        public OperationReply {
            Objects.requireNonNull(lookup, "lookup");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    public enum Lookup { FOUND, NOT_FOUND }

    public record ProtocolSnapshot(
            String tenantId,
            String workOrderId,
            String workerRunId,
            String operationId,
            String status,
            String stableCode,
            String effectAcceptedAt,
            Optional<String> effectTerminalAt,
            String snapshotProof,
            String externalSessionRef,
            Optional<RawCompletion> completion) {}

    public record RawCompletion(
            String schemaVersion,
            String eventId,
            String workOrderId,
            String workerRunId,
            String operationId,
            String status,
            List<RawOutput> outputs,
            Optional<RawTranscript> transcript,
            String stableCode,
            String attemptProof) {}

    public record RawOutput(String relativePath, String sha256, long sizeBytes, String kind) {}

    public record RawTranscript(String relativePath, String sha256, long sizeBytes) {}
}
