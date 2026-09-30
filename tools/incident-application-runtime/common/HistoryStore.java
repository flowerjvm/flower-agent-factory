package io.github.flowerjvm.product.incident;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Only the selected, compile-time module provides persistence. */
interface HistoryStore extends AutoCloseable {
    boolean enabled();
    void save(Map<String, Object> envelope) throws IOException;
    List<Map<String, Object>> list() throws IOException;
    Optional<Map<String, Object>> get(String id) throws IOException;
    @Override void close() throws IOException;

    final class CapacityExceeded extends IOException { CapacityExceeded() { super("HISTORY_CAPACITY"); } }
}
