package io.github.flowerjvm.factory.host;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded read-only local command documents. Deployment owns the binding file's access control. */
final class FactoryLocalDecisionDocuments {
    private static final int MAX_BYTES = 16 * 1024;
    private FactoryLocalDecisionDocuments() {}

    static byte[] read(Path path) {
        try {
            if (!path.isAbsolute() || !path.equals(path.normalize())) throw invalid();
            for (Path current = path; current != null; current = current.getParent()) {
                if (Files.isSymbolicLink(current) || !current.toRealPath().equals(current)) throw invalid();
            }
            var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.size() < 2 || before.size() > MAX_BYTES) throw invalid();
            ByteBuffer bytes = ByteBuffer.allocate(MAX_BYTES + 1);
            try (SeekableByteChannel channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                while (bytes.hasRemaining() && channel.read(bytes) != -1) { /* bounded local file only */ }
            }
            var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || !Objects.equals(before.fileKey(), after.fileKey())
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || after.size() != before.size() || bytes.position() != before.size()) throw invalid();
            return java.util.Arrays.copyOf(bytes.array(), bytes.position());
        } catch (IOException | SecurityException failure) {
            throw invalid();
        }
    }

    static Map<String, Object> decode(byte[] bytes, Set<String> keys) {
        try {
            if (bytes.length > MAX_BYTES) throw invalid();
            ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            Map<String, Object> result = mapper.readValue(bytes, new TypeReference<>() {});
            if (result == null || !result.keySet().equals(keys)) throw invalid();
            return result;
        } catch (IOException failure) {
            // Parser diagnostics can include private document contents. Never relay them.
            throw invalid();
        }
    }

    static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 255
                || !text.equals(text.trim()) || text.chars().anyMatch(Character::isISOControl)) throw invalid();
        return text;
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("LOCAL_DECISION_DOCUMENT_INVALID");
    }
}
