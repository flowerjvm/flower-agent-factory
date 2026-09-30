package io.github.flowerjvm.factory.infrastructure.incidentapplication.tool;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncidentApplicationFilesTest {
    @TempDir Path root;
    @Test void deterministicZipIgnoresCallerMapOrderAndRoundTripsExactBytes() throws Exception {
        var first = new LinkedHashMap<String,byte[]>(); first.put("b.txt", new byte[]{2}); first.put("a/a.txt", new byte[]{1});
        var second = new LinkedHashMap<String,byte[]>(); second.put("a/a.txt", new byte[]{1}); second.put("b.txt", new byte[]{2});
        byte[] zip = IncidentApplicationFiles.zip(first);
        assertArrayEquals(zip, IncidentApplicationFiles.zip(second));
        var decoded = IncidentApplicationFiles.unzip(zip);
        assertEquals(first.keySet(), decoded.keySet()); first.forEach((name, bytes) -> assertArrayEquals(bytes, decoded.get(name)));
    }
    @Test void zipRejectsTraversalWindowsAliasesAndCaseCollisions() {
        for (String path : List.of("../escape", "/absolute", "a/../escape", "a//b", "a\\b", "C:drive", "CON.txt", "a./b"))
            assertThrows(IllegalStateException.class, () -> IncidentApplicationFiles.zip(Map.of(path, new byte[1])), path);
        assertThrows(IllegalStateException.class, () -> IncidentApplicationFiles.zip(Map.of("A.java", new byte[1], "a.java", new byte[1])));
    }
    @Test void rejectsUnboundedFilesAndNoncanonicalOrTrailingArchives() throws Exception {
        assertThrows(IllegalStateException.class, () -> IncidentApplicationFiles.zip(Map.of("big", new byte[IncidentApplicationFiles.MAX_FILE + 1])));
        byte[] valid = IncidentApplicationFiles.zip(Map.of("a", new byte[]{1}));
        assertThrows(IllegalStateException.class, () -> IncidentApplicationFiles.unzip(Arrays.copyOf(valid, valid.length + 1)));
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) { zip.putNextEntry(new ZipEntry("a")); zip.write(1); zip.closeEntry(); }
        assertThrows(IllegalStateException.class, () -> IncidentApplicationFiles.unzip(out.toByteArray()));
    }
    @Test void materializationNeverOverwritesAndCleanupOnlyRemovesExactOwnedChild() throws Exception {
        Path workspace = Files.createTempDirectory(root, "incident-");
        IncidentApplicationFiles.materialize(workspace, Map.of("source/a.txt", new byte[]{1}));
        assertThrows(FileAlreadyExistsException.class, () -> IncidentApplicationFiles.materialize(workspace, Map.of("source/a.txt", new byte[]{2})));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(workspace.resolve("source/a.txt")));
        assertThrows(IllegalStateException.class, () -> IncidentApplicationFiles.removeOwnedWorkspace(root, root));
        Path unrelated = Files.createDirectory(root.resolve("user-data"));
        assertThrows(IllegalStateException.class, () -> IncidentApplicationFiles.removeOwnedWorkspace(unrelated, root));
        IncidentApplicationFiles.removeOwnedWorkspace(workspace, root); assertFalse(Files.exists(workspace)); assertTrue(Files.exists(unrelated));
    }
    @Test void inventoryIsStableAndIncludesHashSizeAndName() {
        assertEquals("pkg/Owner$Inner.class", IncidentApplicationFiles.member("pkg/Owner$Inner.class"));
        var inventory = IncidentApplicationFiles.inventory(Map.of("b", new byte[2], "a", new byte[0]));
        assertEquals(List.of("a", "b"), inventory.stream().map(entry -> entry.get("path")).toList());
        assertEquals(0, inventory.get(0).get("sizeBytes"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", inventory.get(0).get("sha256"));
    }
    @Test void dockerEquipmentAlwaysPinsImageNoNetworkNonrootAndBypassesEntrypoint() {
        var docker = new IncidentApplicationDocker("docker", java.time.Duration.ofSeconds(30));
        var args = docker.create("owned-fixture", "/opt/java/openjdk/bin/javac");
        assertTrue(args.contains("--pull=never")); assertEquals("none", args.get(args.indexOf("--network") + 1));
        assertEquals("1000:1000", args.get(args.indexOf("--user") + 1));
        assertEquals("/opt/java/openjdk/bin/javac", args.get(args.indexOf("--entrypoint") + 1));
        assertTrue(args.contains("--read-only")); assertTrue(args.contains("no-new-privileges"));
        assertFalse(args.contains("--publish")); assertFalse(args.contains("--privileged"));
    }
    @Test void dockerBoundsAreMandatory() {
        assertThrows(IllegalArgumentException.class, () -> new IncidentApplicationDocker("docker", java.time.Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new IncidentApplicationDocker("docker", java.time.Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class, () -> new IncidentApplicationDocker(" ", java.time.Duration.ofSeconds(30)));
    }
}
