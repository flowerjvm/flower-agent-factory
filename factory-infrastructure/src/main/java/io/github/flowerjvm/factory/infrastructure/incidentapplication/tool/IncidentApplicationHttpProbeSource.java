package io.github.flowerjvm.factory.infrastructure.incidentapplication.tool;

/** Inspector source is Factory-owned and is never included on the candidate compilation classpath. */
final class IncidentApplicationHttpProbeSource {
    private IncidentApplicationHttpProbeSource() { }
    static final String SOURCE = """
            import com.fasterxml.jackson.databind.*;
            import com.fasterxml.jackson.databind.node.*;
            import java.net.*;
            import java.net.http.*;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.*;
            import java.time.Duration;
            import java.util.*;
            public final class FactoryIncidentHttpProbe {
              static final ObjectMapper JSON = new ObjectMapper();
              static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
              static final List<String> checks = new ArrayList<>();
              static HttpResponse<byte[]> call(String method, String path, byte[] body) throws Exception {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080" + path)).timeout(Duration.ofSeconds(3));
                if (body != null) request.header("Content-Type", "application/json");
                return HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
              }
              static JsonNode json(HttpResponse<byte[]> response) throws Exception {
                if (response.body().length > 262144) throw new AssertionError("RESPONSE_BOUND");
                return JSON.readTree(response.body());
              }
              static void check(boolean ok, String id) { if (!ok) throw new AssertionError(id); checks.add(id); }
              static void status(HttpResponse<byte[]> response, int status, String id) { check(response.statusCode() == status, id); }
              static void error(byte[] bytes, int expectedStatus, String id) throws Exception {
                var response = call("POST", "/api/investigations", bytes); status(response, expectedStatus, id);
                check(json(response).has("errorCode") && !json(response).has("investigation"), id + "_NO_SUCCESS");
              }
              public static void main(String[] args) {
                Process product = null;
                try {
                  var phases = new ArrayList<Map<String,Object>>();
                  product = startProduct();
                  var initial = run(new String[]{args[0], "initial"}); phases.add(initial);
                  if (args[0].equals("HISTORY")) {
                    long initialPid = product.pid(); stopProduct(product); product = startProduct();
                    if (initialPid == product.pid()) throw new AssertionError("FRESH_JVM_REQUIRED");
                    phases.add(run(new String[]{args[0], "restart", JSON.writeValueAsString(initial.get("ids"))}));
                  } else {
                    try (var files = Files.list(Path.of("/data"))) {
                      if (files.findAny().isPresent()) throw new AssertionError("BASIC_UNEXPECTED_DISK_WRITE");
                    }
                  }
                  stopProduct(product); product = null;
                  System.out.println(JSON.writeValueAsString(Map.of("passed", true, "phases", phases)));
                }
                catch (Throwable failure) {
                  String code = failure instanceof AssertionError && failure.getMessage() != null && failure.getMessage().matches("[A-Z0-9_]+")
                    ? failure.getMessage() : "PRODUCT_EXECUTION_FAILED";
                  System.out.println("{\\"passed\\":false,\\"stableCode\\":\\"WHOLE_PRODUCT_HTTP_FAILED\\",\\"failedCheck\\":\\"" + code + "\\"}");
                }
                finally { if (product != null) try { stopProduct(product); } catch (Exception ignored) { product.destroyForcibly(); } }
              }
              static Process startProduct() throws Exception {
                return new ProcessBuilder("/opt/java/openjdk/bin/java", "-Xmx256m", "-cp", "/product/app.jar:/product/lib/*", "io.github.flowerjvm.product.incident.IncidentApplication")
                  .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
              }
              static void stopProduct(Process product) throws Exception {
                product.destroy();
                if (!product.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                  product.destroyForcibly(); product.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); throw new AssertionError("PRODUCT_STOP_TIMEOUT");
                }
              }
              static Map<String,Object> run(String[] args) throws Exception {
                checks.clear();
                String variant = args[0], phase = args[1]; boolean history = variant.equals("HISTORY");
                JsonNode suite = JSON.readTree(Files.readAllBytes(Path.of("/probe/golden.json")));
                long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (true) {
                  try { if (call("GET", "/api/config", null).statusCode() == 200) break; }
                  catch (Exception unavailable) { }
                  if (System.nanoTime() >= until) throw new AssertionError("STARTUP_TIMEOUT");
                  Thread.sleep(100);
                }
                JsonNode config = json(call("GET", "/api/config", null));
                check(config.size() == 3 && config.path("variant").asText().equals(variant)
                  && config.path("historyEnabled").asBoolean() == history && !config.path("title").asText().isBlank(), "EXACT_VARIANT_CONFIGURATION");
                var ids = new TreeSet<String>();
                if (phase.equals("restart")) {
                  for (JsonNode id : JSON.readTree(args[2])) ids.add(id.asText());
                  JsonNode list = json(call("GET", "/api/history", null)).path("items");
                  var retained = new TreeSet<String>(); for (JsonNode item : list) retained.add(item.path("id").asText());
                  check(retained.equals(ids), "HISTORY_RESTART_PERSISTENCE");
                  var normal = suite.path("cases").get(0);
                  JsonNode response = json(call("POST", "/api/investigations", JSON.writeValueAsBytes(normal.path("input"))));
                  String id = response.path("id").asText();
                  check(ids.contains(id) && response.path("investigation").equals(normal.path("expectedOutput")), "RESTART_IDEMPOTENT_RESULT");
                  check(json(call("GET", "/api/history/" + id, null)).equals(response), "RESTART_HISTORY_QUERY");
                  var report = call("GET", "/api/history/" + id + "/report", null);
                  status(report, 200, "RESTART_REPORT_STATUS");
                  check(new String(report.body(), StandardCharsets.UTF_8).equals(normal.path("expectedOutput").path("reportMarkdown").asText()), "RESTART_REPORT_BYTES");
                  for (int i = 0; i < 100 - ids.size(); i++) {
                    ObjectNode input = normal.path("input").deepCopy(); input.put("incidentId", "CAPACITY-" + i);
                    status(call("POST", "/api/investigations", JSON.writeValueAsBytes(input)), 200, "HISTORY_FILL_" + i);
                  }
                  ObjectNode overflow = normal.path("input").deepCopy(); overflow.put("incidentId", "CAPACITY-OVERFLOW");
                  var full = call("POST", "/api/investigations", JSON.writeValueAsBytes(overflow));
                  status(full, 507, "HISTORY_CAPACITY_BOUND");
                  check(json(full).path("errorCode").asText().equals("HISTORY_CAPACITY"), "HISTORY_CAPACITY_ERROR");
                  status(call("POST", "/api/investigations", JSON.writeValueAsBytes(normal.path("input"))), 200, "FULL_HISTORY_DUPLICATE_STILL_ALLOWED");
                } else {
                  var page = call("GET", "/", null); status(page, 200, "PRODUCT_UI");
                  String html = new String(page.body(), StandardCharsets.UTF_8);
                  var script = call("GET", "/app.js", null); status(script, 200, "PRODUCT_UI_SCRIPT");
                  String javascript = new String(script.body(), StandardCharsets.UTF_8);
                  check(javascript.contains("/api/investigations") && javascript.contains("textContent") && html.contains("download"), "SAFE_UI_AND_REPORT_DOWNLOAD_WIRING");
                  for (JsonNode item : suite.path("cases")) {
                    JsonNode previous = null;
                    for (int repeat = 0; repeat < item.path("repeatInvocations").asInt(); repeat++) {
                      var response = call("POST", "/api/investigations", JSON.writeValueAsBytes(item.path("input")));
                      JsonNode actual = json(response), expected = item.path("expectedOutput");
                      String name = "HTTP_CORE_" + item.path("caseId").asText() + "_" + repeat;
                      if (expected.has("errorCode")) { status(response, 400, name + "_STATUS"); check(actual.equals(expected), name); }
                      else {
                        status(response, 200, name + "_STATUS");
                        check(actual.size() == 2 && actual.path("id").asText().matches("[0-9a-f]{64}") && actual.path("investigation").equals(expected), name);
                        ids.add(actual.path("id").asText());
                        if (previous != null) check(previous.equals(actual), name + "_DETERMINISTIC");
                        previous = actual;
                        if (history) {
                          String id = actual.path("id").asText();
                          check(json(call("GET", "/api/history/" + id, null)).equals(actual), name + "_QUERY");
                          var report = call("GET", "/api/history/" + id + "/report", null);
                          status(report, 200, name + "_REPORT_STATUS");
                          check(new String(report.body(), StandardCharsets.UTF_8).equals(expected.path("reportMarkdown").asText()), name + "_REPORT_BYTES");
                        }
                      }
                    }
                  }
                  error("{".getBytes(StandardCharsets.UTF_8), 400, "MALFORMED_JSON");
                  error("{\\"a\\":1,\\"a\\":2}".getBytes(StandardCharsets.UTF_8), 400, "DUPLICATE_JSON_KEY");
                  error(new byte[]{(byte)0xc3,(byte)0x28}, 400, "INVALID_UTF8");
                  error(("[".repeat(40) + "0" + "]".repeat(40)).getBytes(StandardCharsets.UTF_8), 400, "NESTING_BOUND");
                  error(" ".repeat(65537).getBytes(StandardCharsets.UTF_8), 413, "INPUT_SIZE_BOUND");
                  var foreign = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/investigations"))
                    .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json").header("Origin", "https://foreign.invalid")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                  status(HTTP.send(foreign, HttpResponse.BodyHandlers.ofByteArray()), 403, "FOREIGN_ORIGIN_DENIED");
                  var list = call("GET", "/api/history", null);
                  if (history) {
                    status(list, 200, "HISTORY_LIST_STATUS"); var actualIds = new TreeSet<String>();
                    for (JsonNode item : json(list).path("items")) { check(item.size() == 4, "HISTORY_LIST_SHAPE"); actualIds.add(item.path("id").asText()); }
                    check(actualIds.equals(ids), "HISTORY_DEDUPLICATED_LIST");
                    status(call("GET", "/api/history/" + "0".repeat(64), null), 404, "HISTORY_NOT_FOUND");
                    check(call("GET", "/api/history/%2e%2e/%2e%2e/etc/passwd", null).statusCode() >= 400, "HISTORY_TRAVERSAL_DENIED");
                  } else {
                    status(list, 404, "BASIC_HISTORY_DISABLED"); check(json(list).path("errorCode").asText().equals("HISTORY_DISABLED"), "BASIC_HISTORY_ERROR");
                    for (String id : ids) status(call("GET", "/api/history/" + id, null), 404, "BASIC_HAS_NO_HISTORY_ITEM");
                  }
                }
                var result = new TreeMap<String,Object>(); result.put("passed", true); result.put("stableCode", "WHOLE_PRODUCT_HTTP_PASSED");
                result.put("phase", phase); result.put("variant", variant); result.put("checks", List.copyOf(checks)); result.put("ids", ids);
                return result;
              }
            }
            """;
}
