package io.github.flowerjvm.product.incident;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** HISTORY module: exclusive local store, bounded records, atomic create, no silent pruning. */
final class SelectedHistoryStore implements HistoryStore {
    private final Path root;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<String, Map<String, Object>> records = new TreeMap<>();

    SelectedHistoryStore(Path directory) throws IOException {
        root = directory.toAbsolutePath().normalize();
        requireSafeAncestors(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("HISTORY_DIRECTORY_REQUIRED");
        Path lockPath = root.resolve(".history.lock");
        lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("HISTORY_ALREADY_OPEN");
            load();
            lock = acquired;
        } catch (IOException | RuntimeException failure) {
            if (acquired != null) acquired.release();
            lockChannel.close();
            throw failure;
        }
    }

    public boolean enabled() { return true; }

    public synchronized void save(Map<String, Object> envelope) throws IOException {
        String id = checkedEnvelope(envelope, null);
        byte[] bytes = JsonSupport.bytes(envelope);
        if (bytes.length > JsonSupport.MAX_RECORD_BYTES) throw new IOException("HISTORY_RECORD_TOO_LARGE");
        if (records.containsKey(id)) {
            if (!java.util.Arrays.equals(bytes, JsonSupport.bytes(records.get(id)))) throw new IOException("HISTORY_ID_CONFLICT");
            return;
        }
        if (records.size() >= 100) throw new CapacityExceeded();
        requireSafeAncestors(root);
        Path target = root.resolve(id + ".json");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("HISTORY_STORE_CHANGED");
        Path temporary = root.resolve(".pending-" + id);
        // A crash leaves a diagnostic pending record. Restart fails closed; there is no automatic replay/prune.
        try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) out.write(buffer);
            out.force(true);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        // Linux is the declared runtime platform. Persist the directory entry before returning success.
        try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) { directory.force(true); }
        records.put(id, JsonSupport.object(bytes));
    }

    public synchronized List<Map<String, Object>> list() throws IOException {
        requireSafeAncestors(root);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : records.entrySet()) {
            Map<?, ?> investigation = (Map<?, ?>) entry.getValue().get("investigation");
            result.add(Map.of("id", entry.getKey(), "incidentId", investigation.get("incidentId"),
                    "service", investigation.get("service"), "summary", investigation.get("summary")));
        }
        return List.copyOf(result);
    }

    public synchronized Optional<Map<String, Object>> get(String id) throws IOException {
        requireSafeAncestors(root);
        if (!id.matches("[0-9a-f]{64}")) throw new IOException("INVALID_HISTORY_ID");
        Map<String, Object> record = records.get(id);
        return record == null ? Optional.empty() : Optional.of(JsonSupport.object(JsonSupport.bytes(record)));
    }

    public void close() throws IOException { try { lock.release(); } finally { lockChannel.close(); } }

    private void load() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.equals(".history.lock")) continue;
                if (!name.matches("[0-9a-f]{64}\\.json") || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("HISTORY_STORE_INVALID");
                if (records.size() >= 100 || Files.size(path) > JsonSupport.MAX_RECORD_BYTES)
                    throw new IOException("HISTORY_STORE_INVALID");
                byte[] bytes;
                try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = input.readNBytes(JsonSupport.MAX_RECORD_BYTES + 1);
                }
                if (bytes.length > JsonSupport.MAX_RECORD_BYTES) throw new IOException("HISTORY_STORE_INVALID");
                Map<String, Object> envelope = JsonSupport.object(bytes);
                String id = checkedEnvelope(envelope, name.substring(0, 64));
                records.put(id, envelope);
            }
        }
    }

    private static String checkedEnvelope(Map<String, Object> envelope, String expected) throws IOException {
        if (!envelope.keySet().equals(Set.of("id", "investigation"))
                || !(envelope.get("id") instanceof String id) || !id.matches("[0-9a-f]{64}")
                || (expected != null && !expected.equals(id))
                || !(envelope.get("investigation") instanceof Map<?, ?> investigation)
                || !investigation.keySet().equals(Set.of("incidentId", "service", "summary", "evidence", "findings", "reportMarkdown"))
                || !(investigation.get("incidentId") instanceof String)
                || !(investigation.get("service") instanceof String)
                || !(investigation.get("summary") instanceof String)
                || !(investigation.get("reportMarkdown") instanceof String)
                || !(investigation.get("evidence") instanceof List<?>)
                || !(investigation.get("findings") instanceof List<?>)) throw new IOException("HISTORY_RECORD_INVALID");
        return id;
    }

    private static void requireSafeAncestors(Path root) throws IOException {
        Path cursor = root.getRoot();
        for (Path segment : root) {
            cursor = cursor.resolve(segment);
            if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) throw new IOException("UNSAFE_HISTORY_DIRECTORY");
        }
    }
}
