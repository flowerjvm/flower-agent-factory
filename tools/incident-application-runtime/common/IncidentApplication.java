package io.github.flowerjvm.product.incident;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.flowerjvm.pack.maintenance.InvestigationAcceptanceApi;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Local, bounded product HTTP adapter. No Factory credentials, SDK session, or deployment controller. */
public final class IncidentApplication implements AutoCloseable {
    private static final Set<String> RESULT_FIELDS = Set.of("incidentId", "service", "summary", "evidence", "findings", "reportMarkdown");
    private final HistoryStore history;
    private final HttpServer server;
    private final ThreadPoolExecutor executor;

    public IncidentApplication(int port, Path dataDirectory) throws IOException {
        if (!Set.of("BASIC", "HISTORY").contains(RuntimeConfiguration.VARIANT)
                || RuntimeConfiguration.TITLE.isBlank() || RuntimeConfiguration.TITLE.length() > 120)
            throw new IOException("INVALID_PRODUCT_CONFIGURATION");
        history = new SelectedHistoryStore(dataDirectory);
        if (history.enabled() != RuntimeConfiguration.VARIANT.equals("HISTORY")) {
            history.close();
            throw new IOException("PRODUCT_MODULE_MISMATCH");
        }
        HttpServer created = null;
        try {
            created = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 32);
            server = created;
            executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
                    new ThreadPoolExecutor.AbortPolicy());
            server.setExecutor(executor);
            server.createContext("/", this::handle);
        } catch (IOException | RuntimeException failure) {
            if (created != null) created.stop(0);
            history.close();
            throw failure;
        }
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() throws IOException { server.stop(0); executor.shutdownNow(); history.close(); }

    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IOException("ARGUMENTS_NOT_SUPPORTED");
            System.setProperty("sun.net.httpserver.maxReqTime", "10");
            System.setProperty("sun.net.httpserver.maxRspTime", "10");
            System.setProperty("sun.net.httpserver.maxConnections", "32");
            System.setProperty("sun.net.httpserver.maxReqHeaders", "50");
            System.setProperty("sun.net.httpserver.idleInterval", "10");
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            if (port < 1024 || port > 65535) throw new IOException("INVALID_PORT");
            IncidentApplication app = new IncidentApplication(port,
                    Path.of(System.getenv().getOrDefault("INCIDENT_DATA_DIR", "/data")));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { app.close(); } catch (IOException ignored) { /* Do not log incident/store data. */ }
            }, "incident-product-shutdown"));
            app.start();
            System.out.println("INCIDENT_APPLICATION_READY " + RuntimeConfiguration.VARIANT);
        } catch (Exception failure) {
            System.err.println("INCIDENT_APPLICATION_START_FAILED");
            System.exit(2);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
            if (!sameOrigin(exchange)) { error(exchange, 403, "ORIGIN_NOT_ALLOWED"); return; }
            String path = exchange.getRequestURI().getPath();
            if (!path.equals(exchange.getRequestURI().getRawPath()) || exchange.getRequestURI().getRawQuery() != null) {
                error(exchange, 400, "INVALID_PATH"); return;
            }
            String method = exchange.getRequestMethod();
            if (path.equals("/api/investigations")) {
                if (!method.equals("POST")) { error(exchange, 405, "METHOD_NOT_ALLOWED"); return; }
                investigate(exchange); return;
            }
            if (!method.equals("GET")) { error(exchange, 405, "METHOD_NOT_ALLOWED"); return; }
            if (path.equals("/")) { respond(exchange, 200, "text/html; charset=utf-8", WebPage.HTML.getBytes(StandardCharsets.UTF_8)); return; }
            if (path.equals("/app.js")) { respond(exchange, 200, "text/javascript; charset=utf-8", WebPage.JS.getBytes(StandardCharsets.UTF_8)); return; }
            if (path.equals("/app.css")) { respond(exchange, 200, "text/css; charset=utf-8", WebPage.CSS.getBytes(StandardCharsets.UTF_8)); return; }
            if (path.equals("/api/config")) {
                json(exchange, 200, Map.of("variant", RuntimeConfiguration.VARIANT,
                        "historyEnabled", history.enabled(), "title", RuntimeConfiguration.TITLE)); return;
            }
            if (path.equals("/api/history") || path.startsWith("/api/history/")) {
                if (!history.enabled()) { error(exchange, 404, "HISTORY_DISABLED"); return; }
                if (path.equals("/api/history")) { json(exchange, 200, Map.of("items", history.list())); return; }
                String suffix = path.substring("/api/history/".length());
                boolean report = suffix.endsWith("/report");
                String id = report ? suffix.substring(0, suffix.length() - 7) : suffix;
                if (!id.matches("[0-9a-f]{64}")) { error(exchange, 400, "INVALID_HISTORY_ID"); return; }
                var envelope = history.get(id);
                if (envelope.isEmpty()) { error(exchange, 404, "NOT_FOUND"); return; }
                if (report) {
                    Map<?, ?> investigation = (Map<?, ?>) envelope.orElseThrow().get("investigation");
                    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"investigation-" + id + ".md\"");
                    respond(exchange, 200, "text/markdown; charset=utf-8", ((String) investigation.get("reportMarkdown")).getBytes(StandardCharsets.UTF_8));
                } else json(exchange, 200, envelope.orElseThrow());
                return;
            }
            error(exchange, 404, "NOT_FOUND");
        } catch (IOException | RuntimeException failure) {
            // Every path is bounded, and exception messages may contain input or host paths.
            // Headers already sent/transport failures are closed without leaking diagnostic text.
            try { error(exchange, 500, "APPLICATION_ERROR"); } catch (IOException ignored) { exchange.close(); }
        } finally { exchange.close(); }
    }

    private void investigate(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")) {
            error(exchange, 415, "JSON_CONTENT_TYPE_REQUIRED"); return;
        }
        String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) { error(exchange, 415, "CONTENT_ENCODING_NOT_SUPPORTED"); return; }
        byte[] bytes = exchange.getRequestBody().readNBytes(JsonSupport.MAX_INPUT_BYTES + 1);
        if (bytes.length > JsonSupport.MAX_INPUT_BYTES) { error(exchange, 413, "REQUEST_TOO_LARGE"); return; }
        Map<String, Object> input;
        try { input = JsonSupport.object(bytes); }
        catch (IOException | RuntimeException failure) { error(exchange, 400, "INVALID_JSON"); return; }
        Map<String, Object> result = InvestigationAcceptanceApi.investigate(input);
        if (result != null && result.size() == 1 && result.get("errorCode") instanceof String code
                && Set.of("INVALID_INCIDENT", "EVIDENCE_ID_CONFLICT").contains(code)) {
            error(exchange, 400, code); return;
        }
        if (result == null || !result.keySet().equals(RESULT_FIELDS) || !(result.get("reportMarkdown") instanceof String)) {
            error(exchange, 500, "COMPONENT_RESULT_INVALID"); return;
        }
        Map<String, Object> envelope = Map.of("id", JsonSupport.id(input), "investigation", result);
        try { history.save(envelope); }
        catch (HistoryStore.CapacityExceeded failure) { error(exchange, 507, "HISTORY_CAPACITY"); return; }
        catch (IOException failure) { error(exchange, 503, "HISTORY_UNAVAILABLE"); return; }
        json(exchange, 200, envelope);
    }

    private static boolean sameOrigin(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || !host.matches("(?i)(localhost|127\\.0\\.0\\.1|\\[::1\\])(?::[0-9]{1,5})?")) return false;
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        return origin == null || origin.equals("http://" + host);
    }

    private static void error(HttpExchange exchange, int status, String code) throws IOException {
        json(exchange, status, Map.of("errorCode", code));
    }
    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        respond(exchange, status, "application/json; charset=utf-8", JsonSupport.bytes(value));
    }
    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        if (body.length > JsonSupport.MAX_RECORD_BYTES) throw new IOException("RESPONSE_TOO_LARGE");
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
