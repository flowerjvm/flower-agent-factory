package io.github.flowerjvm.factory.infrastructure.incidentapplication.tool;

import static io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationArtifacts.hash;
import static org.junit.jupiter.api.Assertions.*;
import io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationOrder;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in real sandbox tests. Historical source fixture bytes are used solely to test equipment;
 * these tests do not claim a current certificate, create production orders, or release products.
 */
public class IncidentApplicationToolNativeSandboxIT {
    @TempDir Path workspace;
    private Set<String> containersBefore;
    private Set<String> consumerContainersBefore;
    private Set<String> consumerContainers() {
        var control = new IncidentApplicationDocker(System.getProperty("factory.incident.docker", "C:/Program Files/Docker/Docker/resources/bin/docker.exe"), Duration.ofSeconds(15));
        var result = control.call(List.of("ps", "-a", "--filter", "label=io.github.flowerjvm.incident-local.owner", "--format", "{{.Names}}"));
        assertEquals(0, result.exitCode(), "consumer cleanup audit must be available");
        return new TreeSet<>(result.output().lines().filter(line -> !line.isBlank()).toList());
    }
    private Set<String> ownedContainers() {
        var control = new IncidentApplicationDocker(System.getProperty("factory.incident.docker", "C:/Program Files/Docker/Docker/resources/bin/docker.exe"), Duration.ofSeconds(15));
        var result = control.call(List.of("ps", "-a", "--filter", "label=" + IncidentApplicationDocker.LABEL, "--format", "{{.Names}}"));
        assertEquals(0, result.exitCode(), "Docker cleanup audit must be available");
        return new TreeSet<>(result.output().lines().filter(line -> !line.isBlank()).toList());
    }
    @BeforeEach void snapshotOwnedContainers() { containersBefore = ownedContainers(); consumerContainersBefore = consumerContainers(); }
    @AfterEach void everyCreatedEquipmentContainerWasRemoved() {
        assertEquals(containersBefore, ownedContainers(), "test must not leave even stopped or created equipment containers");
        assertEquals(consumerContainersBefore, consumerContainers(), "consumer startup failure must not leave even stopped or created containers");
    }
    private Path repository() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        return Files.isDirectory(directory.resolve("tools/incident-application-runtime")) ? directory : directory.getParent();
    }
    private Path mavenRepository() { return Path.of(System.getProperty("user.home"), ".m2", "repository"); }
    private DockerIncidentApplicationProductionTool equipment() {
        var inaccessible = new ArtifactStore() {
            @Override public ArtifactReference store(Artifact value) { throw new AssertionError("native equipment test must not issue Factory artifacts"); }
            @Override public Optional<Artifact> find(TenantId tenant, ArtifactReference ref) { throw new AssertionError("no synthetic current-certification authority"); }
        };
        String executable = System.getProperty("factory.incident.docker", "C:/Program Files/Docker/Docker/resources/bin/docker.exe");
        return new DockerIncidentApplicationProductionTool(inaccessible, (tenant, ref) -> { throw new AssertionError("no production component resolution in equipment fixture"); },
                repository().resolve("tools/incident-application-runtime"), mavenRepository(), workspace, executable, Duration.ofSeconds(60));
    }
    private Map<String,byte[]> libraries() throws Exception {
        var result = new TreeMap<String,byte[]>();
        for (var entry : DockerIncidentApplicationProductionTool.LIBRARIES.entrySet()) {
            byte[] bytes = IncidentApplicationFiles.read(mavenRepository().resolve(entry.getKey()), IncidentApplicationFiles.MAX_FILE);
            assertEquals(entry.getValue(), hash(bytes)); result.put("lib/" + Path.of(entry.getKey()).getFileName(), bytes);
        }
        return result;
    }
    private Map<String,byte[]> sources(String module, String configuration) throws Exception {
        Path runtime = repository().resolve("tools/incident-application-runtime");
        var result = new TreeMap<String,byte[]>();
        try (var files = Files.list(runtime.resolve("common"))) {
            for (Path source : files.toList()) result.put("src/" + source.getFileName(), IncidentApplicationFiles.read(source, 262144));
        }
        result.put("src/SelectedHistoryStore.java", IncidentApplicationFiles.read(runtime.resolve(module + "/SelectedHistoryStore.java"), 262144));
        result.put("src/RuntimeConfiguration.java", ("package io.github.flowerjvm.product.incident; public final class RuntimeConfiguration { public static final String VARIANT = \""
                + configuration + "\"; public static final String TITLE = \"Factory equipment fixture\"; }").getBytes(StandardCharsets.UTF_8));
        Path core = Path.of(System.getProperty("factory.incident.native.componentSourceDirectory",
                repository().resolve("demo-handoff-20260908-repair-ra001/product-source/src/main/java/io/github/flowerjvm/pack/maintenance").toString()));
        var hashes = Map.of("IncidentInvestigator.java", "e256a77e59f721419f1ca31151d4503424e7fcffdc58b8039045ac60fe7455a9",
                "InvestigationAcceptanceApi.java", "cf193ad9d0d10171a124007bf1da102f8e5483126183da4c3d736226c83c85b4");
        for (var entry : hashes.entrySet()) {
            byte[] bytes = IncidentApplicationFiles.read(core.resolve(entry.getKey()), 262144); assertEquals(entry.getValue(), hash(bytes));
            result.put("src/" + entry.getKey(), bytes);
        }
        return result;
    }
    @Test void sameEquipmentBuildsBasicAndHistoryAndIndependentHttpVerifiesBoth() throws Exception {
        var tool = equipment(); var libs = libraries(); var basicSources = sources("basic", "BASIC");
        byte[] basic;
        try { basic = tool.compileApplication(basicSources, libs); }
        catch (IncidentApplicationDocker.EquipmentFailure failure) { throw new AssertionError(failure.equipmentOutput(), failure); }
        assertArrayEquals(basic, tool.compileApplication(basicSources, libs), "reproducible javac bytecode and archive");
        byte[] history = tool.compileApplication(sources("history", "HISTORY"), libs);
        assertNotEquals(hash(basic), hash(history));
        String selected = "io/github/flowerjvm/product/incident/SelectedHistoryStore.class";
        assertFalse(new String(IncidentApplicationFiles.unzip(basic).get(selected), StandardCharsets.ISO_8859_1).contains("java/nio/file/Files"));
        assertTrue(new String(IncidentApplicationFiles.unzip(history).get(selected), StandardCharsets.ISO_8859_1).contains("java/nio/file/Files"));
        var basicBundle = new TreeMap<>(libs); basicBundle.put("app.jar", basic);
        var basicReport = tool.inspectHttp(IncidentApplicationOrder.Variant.BASIC, basicBundle);
        assertEquals(1, basicReport.size()); assertTrue(basicReport.stream().allMatch(phase -> Boolean.TRUE.equals(phase.get("passed"))), basicReport.toString());
        var historyBundle = new TreeMap<>(libs); historyBundle.put("app.jar", history);
        var historyReport = tool.inspectHttp(IncidentApplicationOrder.Variant.HISTORY, historyBundle);
        assertEquals(2, historyReport.size()); assertTrue(historyReport.stream().allMatch(phase -> Boolean.TRUE.equals(phase.get("passed"))), historyReport.toString());
        assertTrue(((List<?>)historyReport.get(1).get("checks")).contains("HISTORY_RESTART_PERSISTENCE"));
    }
    @Test void goodUnchangedCoreWithWrongModuleWiringFailsIndependentWholeProductGate() throws Exception {
        var tool = equipment(); var libs = libraries();
        var product = new TreeMap<>(libs);
        product.put("app.jar", tool.compileApplication(sources("basic", "HISTORY"), libs));
        // The original good core is included unchanged, yet mismatched product wiring cannot pass.
        var report = tool.inspectHttp(IncidentApplicationOrder.Variant.HISTORY, product);
        assertEquals(1, report.size());
        assertEquals(Boolean.FALSE, report.get(0).get("passed"));
        assertEquals("WHOLE_PRODUCT_HTTP_FAILED", report.get(0).get("stableCode"));
    }
    @Test void consumerLauncherServesLoopbackCleansOnlyOwnedResourcesAndKeepsHistoryAcrossInvocations() throws Exception {
        var tool = equipment(); var libs = libraries();
        Path basic = launcherFixture("basic", tool.compileApplication(sources("basic", "BASIC"), libs), libs);
        launchAndCheck(basic, false, null);
        assertFalse(Files.exists(basic.resolve("incident-data")), "BASIC launcher must not create persistent storage");
        Path history = launcherFixture("history", tool.compileApplication(sources("history", "HISTORY"), libs), libs);
        JsonNode result = launchAndCheck(history, true, null);
        Path saved = history.resolve("incident-data").resolve(result.path("id").asText() + ".json");
        assertTrue(Files.isRegularFile(saved)); byte[] firstBytes = Files.readAllBytes(saved);
        launchAndCheck(history, true, result);
        assertArrayEquals(firstBytes, Files.readAllBytes(saved), "new consumer container must read the same unmodified persisted result");
    }
    private Path launcherFixture(String variant, byte[] app, Map<String,byte[]> libs) throws Exception {
        Path root = Files.createTempDirectory(workspace, "consumer-" + variant + "-");
        var files = new TreeMap<>(libs); files.put("app.jar", app);
        files.put("run-local.ps1", IncidentApplicationLauncherSource.POWERSHELL.getBytes(StandardCharsets.UTF_8));
        // Isolated launcher fixture, not a Factory certificate or release evidence.
        var json = new ObjectMapper();
        files.put("BOM.json", json.writeValueAsBytes(Map.of("schemaVersion", "factory.incident-application.bill-of-materials.v1",
                "order", Map.of("variant", variant.toUpperCase(Locale.ROOT)), "appJarSha256", hash(app), "runtimeLibraries", IncidentApplicationFiles.inventory(libs))));
        files.put("product-config.json", json.writeValueAsBytes(Map.of("variant", variant.toUpperCase(Locale.ROOT), "historyEnabled", variant.equals("history"), "title", "Factory equipment fixture")));
        IncidentApplicationFiles.materialize(root, files); return root;
    }
    private JsonNode launchAndCheck(Path bundle, boolean history, JsonNode retained) throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        String shell = System.getProperty("factory.incident.native.powershell", "pwsh");
        var start = new ProcessBuilder(shell, "-NoProfile", "-File", bundle.resolve("run-local.ps1").toString(), "-Port", Integer.toString(port), "-MaxSeconds", "20");
        start.redirectErrorStream(true); Process launcher = start.start();
        var captured = new java.util.concurrent.CompletableFuture<String>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var input = launcher.getInputStream()) { byte[] bytes = input.readNBytes(65537); if (bytes.length > 65536) throw new java.io.IOException("launcher output bound"); captured.complete(new String(bytes, StandardCharsets.UTF_8)); }
            catch (Exception failure) { captured.completeExceptionally(failure); }
        });
        String owner = null; var json = new ObjectMapper(); JsonNode result = retained;
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        String base = "http://127.0.0.1:" + port;
        var control = new IncidentApplicationDocker(System.getProperty("factory.incident.docker", "C:/Program Files/Docker/Docker/resources/bin/docker.exe"), Duration.ofSeconds(15));
        try {
            long until = System.nanoTime() + Duration.ofSeconds(25).toNanos(); boolean ready = false;
            while (launcher.isAlive() && System.nanoTime() < until) {
                try {
                    var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/config")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        assertEquals(history, json.readTree(response.body()).path("historyEnabled").asBoolean()); ready = true; break;
                    }
                } catch (java.io.IOException notReady) { }
                Thread.sleep(100);
            }
            if (!ready) {
                if (!launcher.isAlive()) throw new AssertionError(captured.get(5, TimeUnit.SECONDS));
                throw new AssertionError("LOCAL_CONSUMER_HTTP_NOT_READY");
            }
            var owned = control.call(List.of("ps", "--filter", "label=io.github.flowerjvm.incident-local.owner",
                    "--filter", "label=io.github.flowerjvm.incident-local.port=" + port, "--format", "{{.Names}}"));
            assertEquals(0, owned.exitCode());
            var matches = owned.output().lines().filter(line -> !line.isBlank()).toList();
            assertEquals(1, matches.size(), owned.output()); String name = matches.get(0);
            assertTrue(name.matches("incident-local-[0-9a-f]{32}"), name);
            owner = name.substring("incident-local-".length());
            var inspection = control.call(List.of("inspect", name)); assertEquals(0, inspection.exitCode());
            JsonNode isolated = json.readTree(inspection.output()).get(0);
            assertEquals("none", isolated.path("HostConfig").path("NetworkMode").asText(), "product must have no external network interface");
            assertTrue(isolated.path("HostConfig").path("ReadonlyRootfs").asBoolean());
            assertEquals("1000:1000", isolated.path("Config").path("User").asText());
            assertEquals(0, isolated.path("HostConfig").path("PortBindings").size(), "no Docker published ingress bypasses the restricted relay");
            assertEquals(1, isolated.path("NetworkSettings").path("Networks").size());
            assertTrue(isolated.path("NetworkSettings").path("Networks").has("none"));
            assertEquals("", isolated.path("NetworkSettings").path("Networks").path("none").path("Gateway").asText());
            var page = client.send(HttpRequest.newBuilder(URI.create(base + "/")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode()); assertTrue(page.body().contains("/app.js"));
            var script = client.send(HttpRequest.newBuilder(URI.create(base + "/app.js")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, script.statusCode()); assertTrue(script.body().contains("textContent")); assertTrue(script.body().contains("download"));
            var foreign = client.send(HttpRequest.newBuilder(URI.create(base + "/api/config")).timeout(Duration.ofSeconds(3))
                    .header("Origin", "https://foreign.invalid").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, foreign.statusCode()); assertTrue(foreign.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            var unsupported = client.send(HttpRequest.newBuilder(URI.create(base + "/api/config")).timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, unsupported.statusCode());
            var target = client.send(HttpRequest.newBuilder(URI.create(base + "/api/config?url=http://foreign.invalid")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, target.statusCode(), "relay must not accept arbitrary destinations or query parameters");
            var oversize = client.send(HttpRequest.newBuilder(URI.create(base + "/api/investigations")).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(new byte[65537])).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(413, oversize.statusCode());
            if (retained == null) {
                JsonNode normal = json.readTree(io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract.caseSuiteBytes()).path("cases").get(0);
                var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/investigations")).timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(normal.path("input")))).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode()); result = json.readTree(response.body()); assertEquals(normal.path("expectedOutput"), result.path("investigation"));
            } else {
                var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/history/" + retained.path("id").asText())).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode()); assertEquals(retained, json.readTree(response.body()));
            }
            if (history) {
                var report = client.send(HttpRequest.newBuilder(URI.create(base + "/api/history/" + result.path("id").asText() + "/report"))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, report.statusCode()); assertTrue(report.headers().firstValue("Content-Disposition").orElse("").contains(result.path("id").asText()));
            }
            assertTrue(launcher.waitFor(40, TimeUnit.SECONDS), "bounded consumer must exit without a background service");
            String output = captured.get(5, TimeUnit.SECONDS); reader.join(5000); assertEquals(0, launcher.exitValue(), output);
            assertTrue(output.contains("Local demo stopped."), output);
            var containers = control.call(List.of("ps", "-a", "--filter", "label=io.github.flowerjvm.incident-local.owner=" + owner, "--format", "{{.Names}}"));
            assertEquals(0, containers.exitCode()); assertEquals("", containers.output().trim());
            var networks = control.call(List.of("network", "ls", "--filter", "label=io.github.flowerjvm.incident-local.owner=" + owner, "--format", "{{.Name}}"));
            assertEquals(0, networks.exitCode()); assertEquals("", networks.output().trim());
            return result;
        } finally {
            // Give the bounded script its normal cleanup window even when an HTTP assertion fails.
            if (launcher.isAlive() && !launcher.waitFor(30, TimeUnit.SECONDS)) launcher.destroyForcibly();
            if (owner != null) {
                var stillThere = control.call(List.of("ps", "-a", "--filter", "label=io.github.flowerjvm.incident-local.owner=" + owner, "--format", "{{.Names}}"));
                assertEquals(0, stillThere.exitCode());
                if (!stillThere.output().isBlank()) {
                    assertEquals("incident-local-" + owner, stillThere.output().trim()); control.require(List.of("rm", "-f", "incident-local-" + owner));
                }
                var network = control.call(List.of("network", "ls", "--filter", "label=io.github.flowerjvm.incident-local.owner=" + owner, "--format", "{{.Name}}"));
                assertEquals(0, network.exitCode());
                if (!network.output().isBlank()) { assertEquals("incident-local-net-" + owner, network.output().trim()); control.require(List.of("network", "rm", "incident-local-net-" + owner)); }
            }
        }
    }
}
