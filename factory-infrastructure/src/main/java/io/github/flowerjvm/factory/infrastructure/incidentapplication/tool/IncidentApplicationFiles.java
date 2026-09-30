package io.github.flowerjvm.factory.infrastructure.incidentapplication.tool;

import static io.github.flowerjvm.factory.application.incidentapplication.IncidentApplicationArtifacts.hash;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;

/** Bounded deterministic packaging. ZIP names are never trusted as host filesystem paths. */
final class IncidentApplicationFiles {
    static final int MAX_FILE = 8 * 1024 * 1024;
    static final int MAX_TOTAL = 24 * 1024 * 1024;
    private IncidentApplicationFiles() { }
    static IllegalStateException invalid() { return new IllegalStateException("INCIDENT_APPLICATION_ARTIFACT_INVALID"); }
    static String member(String value) {
        if (value == null || value.length() > 240 || !value.matches("[A-Za-z0-9.$_/-]+") || value.startsWith("/")
                || value.endsWith("/") || value.contains("//")) throw invalid();
        for (String part : value.split("/")) if (part.equals(".") || part.equals("..") || part.endsWith(".")
                || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) throw invalid();
        return value;
    }
    static void noLinks(Path path) throws IOException {
        for (Path at = path.toAbsolutePath().normalize(); at != null; at = at.getParent()) {
            if (Files.exists(at, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files.readAttributes(at, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw invalid();
            }
        }
    }
    static Path safeDirectory(Path path) throws IOException {
        Path result = path.toAbsolutePath().normalize(); noLinks(result);
        if (result.getParent() == null || result.toString().contains(",") || result.toString().chars().anyMatch(Character::isISOControl)) throw invalid();
        Files.createDirectories(result); noLinks(result); return result;
    }
    static byte[] read(Path path, int maximum) throws IOException {
        noLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maximum) throw invalid();
        try (var input = Files.newInputStream(path)) { byte[] bytes = input.readNBytes(maximum + 1); if (bytes.length > maximum) throw invalid(); return bytes; }
    }
    static void materialize(Path root, Map<String, byte[]> members) throws IOException {
        safeDirectory(root);
        for (var entry : members.entrySet()) {
            Path target = root.resolve(member(entry.getKey())).normalize();
            if (!target.startsWith(root)) throw invalid();
            safeDirectory(target.getParent()); noLinks(target);
            Files.write(target, entry.getValue(), StandardOpenOption.CREATE_NEW);
        }
    }
    static SortedMap<String, byte[]> readTree(Path root) throws IOException {
        noLinks(root); var result = new TreeMap<String, byte[]>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                noLinks(path);
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (result.size() >= 256) throw invalid();
                    result.put(member(root.relativize(path).toString().replace('\\', '/')), read(path, MAX_FILE));
                }
            }
        }
        if (result.values().stream().mapToLong(bytes -> bytes.length).sum() > MAX_TOTAL) throw invalid();
        return result;
    }
    static byte[] zip(Map<String, byte[]> files) throws IOException {
        var out = new ByteArrayOutputStream(); var folded = new HashSet<String>(); long total = 0;
        try (var zip = new ZipOutputStream(out)) {
            for (var entry : new TreeMap<>(files).entrySet()) {
                String name = member(entry.getKey()); byte[] bytes = entry.getValue();
                if (!folded.add(name.toLowerCase(Locale.ROOT)) || bytes.length > MAX_FILE || (total += bytes.length) > MAX_TOTAL) throw invalid();
                var item = new ZipEntry(name); item.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                item.setMethod(ZipEntry.STORED); item.setSize(bytes.length); item.setCompressedSize(bytes.length);
                var crc = new CRC32(); crc.update(bytes); item.setCrc(crc.getValue()); zip.putNextEntry(item); zip.write(bytes); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
    static SortedMap<String, byte[]> unzip(byte[] bytes) throws IOException {
        if (bytes.length > MAX_TOTAL + 128 * 1024) throw invalid();
        var result = new TreeMap<String, byte[]>(); var folded = new HashSet<String>(); long total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = member(entry.getName());
                if (result.size() >= 256 || entry.isDirectory() || !folded.add(name.toLowerCase(Locale.ROOT))) throw invalid();
                byte[] content = zip.readNBytes(MAX_FILE + 1);
                if (content.length > MAX_FILE || (total += content.length) > MAX_TOTAL) throw invalid();
                result.put(name, content);
            }
        }
        // Reject trailing data, alternate ZIP metadata and noncanonical archives. The Factory only emits this encoding.
        if (!Arrays.equals(bytes, zip(result))) throw invalid();
        return result;
    }
    static List<Map<String,Object>> inventory(Map<String, byte[]> files) {
        return new TreeMap<>(files).entrySet().stream().map(entry -> Map.<String,Object>of(
                "path", member(entry.getKey()), "sha256", hash(entry.getValue()), "sizeBytes", entry.getValue().length)).toList();
    }
    static void removeOwnedWorkspace(Path workspace, Path parent) throws IOException {
        Path target = workspace.toAbsolutePath().normalize(); Path boundary = parent.toAbsolutePath().normalize();
        if (!target.getParent().equals(boundary) || !target.getFileName().toString().startsWith("incident-")) throw invalid();
        noLinks(target);
        if (!Files.exists(target)) return;
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) { noLinks(path); Files.delete(path); }
        }
    }
}
