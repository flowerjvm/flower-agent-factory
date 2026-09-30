package io.github.flowerjvm.product.incident;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Standalone container-only module tests; separate from Factory independent whole-product verification. */
public final class IncidentApplicationRuntimeTest {
    private static int tests;
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final String INPUT = """
            {"incidentId":"INC-한글","service":"주문 API","summary":"<script>private</script>","observations":[
            {"evidenceId":"EV-2","source":"metrics","observedAt":"2026-09-12T12:01:00Z","kind":"metric","message":"5XX ERROR RATE"},
            {"evidenceId":"EV-1","source":"logs","observedAt":"2026-09-12T12:00:00Z","kind":"log","message":"TIMEOUT"}]}
            """;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("NO_ARGS");
        Path parent = Files.createTempDirectory("incident-runtime-test-");
        Path data = parent.resolve("data");
        boolean history = RuntimeConfiguration.VARIANT.equals("HISTORY");
        if (history) Files.createDirectory(data);
        String id;
        String report;
        try (var app = new IncidentApplication(0, data)) {
            app.start();
            var config = object(get(app, "/api/config"));
            check(config.get("variant").equals(RuntimeConfiguration.VARIANT), "CONFIG_VARIANT");
            check(config.get("historyEnabled").equals(history), "CONFIG_SELECTED_MODULE");
            check(config.get("title").equals(RuntimeConfiguration.TITLE), "IMMUTABLE_TITLE");
            var page = get(app, "/");
            check(page.statusCode() == 200 && text(page).contains("lang=\"ko\""), "KOREAN_UI");
            check(page.headers().firstValue("content-security-policy").orElse("").contains("script-src 'self'"), "CSP_SELF_ONLY");
            check(!text(get(app, "/app.js")).contains("innerHTML") && text(get(app, "/app.js")).contains("textContent"), "UI_TEXT_NOT_HTML");
            var result = post(app, INPUT);
            check(result.statusCode() == 200, "HTTP_INVESTIGATE");
            var envelope = object(result);
            id = (String) envelope.get("id");
            check(id.matches("[0-9a-f]{64}"), "CONTENT_ID");
            Map<?, ?> investigation = (Map<?, ?>) envelope.get("investigation");
            check(investigation.get("service").equals("주문 API"), "UTF8_ROUNDTRIP");
            report = (String) investigation.get("reportMarkdown");
            check(report.contains("SERVICE_TIMEOUT [HIGH]") && report.contains("ERROR_RATE_ELEVATED [HIGH]"), "ACTUAL_COMPONENT_CASE_INSENSITIVE");
            check(report.contains("<script>private</script>"), "OUTPUT_RETAINS_TEXT_NOT_EXECUTED");
            check(object(post(app, INPUT)).get("id").equals(id), "DUPLICATE_IDEMPOTENT");
            var rearranged = new java.util.LinkedHashMap<>(JsonSupport.object(INPUT.getBytes(StandardCharsets.UTF_8)));
            rearranged.remove("incidentId"); rearranged.put("incidentId", "INC-한글");
            check(object(post(app, new String(JsonSupport.bytes(rearranged), StandardCharsets.UTF_8))).get("id").equals(id), "OBJECT_KEY_ORDER_IDEMPOTENT");
            error(post(app, "{}"), 400, "INVALID_INCIDENT", "DOMAIN_ERROR");
            error(post(app, "{"), 400, "INVALID_JSON", "MALFORMED_JSON");
            error(post(app, "{\"x\":1,\"x\":2}"), 400, "INVALID_JSON", "DUPLICATE_JSON_KEYS");
            error(post(app, "{} {}"), 400, "INVALID_JSON", "TRAILING_JSON");
            error(post(app, "null"), 400, "INVALID_JSON", "NULL_JSON");
            error(post(app, "{\"x\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}"), 400, "INVALID_JSON", "DEPTH_LIMIT");
            error(post(app, " ".repeat(65_537)), 413, "REQUEST_TOO_LARGE", "INPUT_SIZE_LIMIT");
            error(send(app, "POST", "/api/investigations", new byte[]{(byte) 0xc0, (byte) 0xaf}, "application/json", null), 400, "INVALID_JSON", "UTF8_INVALID");
            error(send(app, "POST", "/api/investigations", INPUT.getBytes(StandardCharsets.UTF_8), "text/plain", null), 415, "JSON_CONTENT_TYPE_REQUIRED", "JSON_CONTENT_TYPE");
            error(send(app, "POST", "/api/investigations", INPUT.getBytes(StandardCharsets.UTF_8), "application/json", "https://evil.example"), 403, "ORIGIN_NOT_ALLOWED", "CROSS_ORIGIN_REJECTED");
            check(result.headers().firstValue("access-control-allow-origin").isEmpty(), "NO_CORS");
            error(get(app, "/api/investigations"), 405, "METHOD_NOT_ALLOWED", "METHOD_ENFORCED");
            error(get(app, "/api/history/%2e%2e"), 400, "INVALID_PATH", "ENCODED_TRAVERSAL_REJECTED");
            error(get(app, "/api/config?secret=unused"), 400, "INVALID_PATH", "QUERY_NOT_ACCEPTED");
            if (history) {
                check(((List<?>) object(get(app, "/api/history")).get("items")).size() == 1, "HISTORY_EXACT_DUPLICATE_SINGLE_RECORD");
                check(object(get(app, "/api/history/" + id)).equals(envelope), "HISTORY_RETRIEVE_ENVELOPE");
                check(text(get(app, "/api/history/" + id + "/report")).equals(report), "HISTORY_REPORT_EXPORT");
                error(get(app, "/api/history/" + "0".repeat(64)), 404, "NOT_FOUND", "HISTORY_NOT_FOUND");
                error(get(app, "/api/history/../../etc/passwd"), 400, "INVALID_HISTORY_ID", "RAW_TRAVERSAL_REJECTED");
                try (var pool = Executors.newFixedThreadPool(4)) {
                    List<java.util.concurrent.Future<Integer>> jobs = new ArrayList<>();
                    for (int n = 0; n < 8; n++) jobs.add(pool.submit(() -> post(app, INPUT).statusCode()));
                    for (var job : jobs) if (job.get() != 200) throw new AssertionError("PARALLEL_DUPLICATE_FAILED");
                }
                check(((List<?>) object(get(app, "/api/history")).get("items")).size() == 1, "PARALLEL_DUPLICATE_ONE_RECORD");
                boolean denied = false;
                try (var ignored = new SelectedHistoryStore(data)) { /* Must not acquire concurrent ownership. */ }
                catch (IOException | RuntimeException expected) { denied = true; }
                check(denied, "EXCLUSIVE_STORE_OWNER");
            } else {
                error(get(app, "/api/history"), 404, "HISTORY_DISABLED", "BASIC_HISTORY_DISABLED");
                error(get(app, "/api/history/" + id + "/report"), 404, "HISTORY_DISABLED", "BASIC_HISTORY_EXPORT_DISABLED");
                check(!Files.exists(data), "BASIC_NO_FILESYSTEM_WRITES");
            }
        }
        try (var restarted = new IncidentApplication(0, data)) {
            restarted.start();
            if (history) {
                check(object(get(restarted, "/api/history/" + id)).get("id").equals(id), "RESTART_PRESERVES_RECORD");
                check(text(get(restarted, "/api/history/" + id + "/report")).equals(report), "RESTART_PRESERVES_REPORT");
                for (int n = 1; n < 100; n++) {
                    var response = post(restarted, INPUT.replace("INC-한글", "INC-" + n));
                    if (response.statusCode() != 200) throw new AssertionError("CAPACITY_FILL_FAILED");
                }
                error(post(restarted, INPUT.replace("INC-한글", "INC-overflow")), 507, "HISTORY_CAPACITY", "BOUNDED_HUNDRED_RECORDS");
                check(post(restarted, INPUT).statusCode() == 200, "FULL_STORE_DUPLICATE_STILL_IDEMPOTENT");
                check(((List<?>) object(get(restarted, "/api/history")).get("items")).size() == 100, "NO_SILENT_EVICTION");
            } else check(!Files.exists(data), "BASIC_RESTART_NO_FILESYSTEM_WRITES");
        }
        if (history) {
            Path symlink = parent.resolve("symlink"); Files.createSymbolicLink(symlink, data);
            expectStoreRejected(symlink, "SYMLINK_ROOT_REJECTED");
            Path records = Files.createDirectory(parent.resolve("symlink-record"));
            Files.createSymbolicLink(records.resolve("0".repeat(64) + ".json"), data.resolve(id + ".json"));
            expectStoreRejected(records, "SYMLINK_RECORD_REJECTED");
            Path corrupt = Files.createDirectory(parent.resolve("corrupt"));
            Files.writeString(corrupt.resolve("0".repeat(64) + ".json"), "{}");
            expectStoreRejected(corrupt, "CORRUPT_RECORD_REJECTED");
            Path pending = Files.createDirectory(parent.resolve("pending"));
            Files.writeString(pending.resolve(".pending-" + "0".repeat(64)), "incomplete");
            expectStoreRejected(pending, "CRASH_PENDING_MANUAL_REVIEW");
        }
        System.out.println("RUNTIME_TESTS_PASSED " + RuntimeConfiguration.VARIANT + " " + tests);
    }

    private static void expectStoreRejected(Path path, String name) throws Exception {
        boolean rejected = false;
        try (var ignored = new SelectedHistoryStore(path)) { /* invalid store must be rejected */ }
        catch (IOException expected) { rejected = true; }
        check(rejected, name);
    }
    private static HttpResponse<byte[]> get(IncidentApplication app, String path) throws Exception { return send(app, "GET", path, null, null, null); }
    private static HttpResponse<byte[]> post(IncidentApplication app, String input) throws Exception { return send(app, "POST", "/api/investigations", input.getBytes(StandardCharsets.UTF_8), "application/json", null); }
    private static HttpResponse<byte[]> send(IncidentApplication app, String method, String path, byte[] body, String type, String origin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path)).timeout(Duration.ofSeconds(5))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (type != null) builder.header("Content-Type", type);
        if (origin != null) builder.header("Origin", origin);
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static Map<String, Object> object(HttpResponse<byte[]> response) throws Exception { return JsonSupport.object(response.body()); }
    private static String text(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }
    private static void error(HttpResponse<byte[]> response, int status, String code, String name) throws Exception {
        check(response.statusCode() == status && object(response).equals(Map.of("errorCode", code)), name);
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        tests++; System.out.println("PASS " + RuntimeConfiguration.VARIANT + " " + name);
    }
}
