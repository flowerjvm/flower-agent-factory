package io.github.flowerjvm.product.incident;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** BASIC module: constructor and methods perform no filesystem operations. */
final class SelectedHistoryStore implements HistoryStore {
    SelectedHistoryStore(Path ignored) throws IOException {}
    public boolean enabled() { return false; }
    public void save(Map<String, Object> envelope) {}
    public List<Map<String, Object>> list() { return List.of(); }
    public Optional<Map<String, Object>> get(String id) { return Optional.empty(); }
    public void close() {}
}
