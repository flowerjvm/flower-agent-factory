package io.github.flowerjvm.factory.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapabilities;
import io.github.flowerjvm.factory.contracts.worker.WorkerCapability;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class JdbcJsonCodec {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    JdbcJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy();
    }

    String writeStrings(Collection<String> values, boolean preserveOrder) {
        List<String> normalized = preserveOrder
                ? List.copyOf(values)
                : values.stream().sorted().toList();
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException exception) {
            throw new FactoryPersistenceException("JSON serialization failed", exception);
        }
    }

    String writeCapabilities(Collection<WorkerCapability> values) {
        return writeStrings(
                values.stream().map(WorkerCapability::value).sorted(Comparator.naturalOrder()).toList(),
                true);
    }

    List<String> readStringList(String json) {
        try {
            return List.copyOf(objectMapper.readValue(json, STRING_LIST));
        } catch (JsonProcessingException exception) {
            throw new FactoryPersistenceException("JSON deserialization failed", exception);
        }
    }

    Set<String> readStringSet(String json) {
        return Set.copyOf(new LinkedHashSet<>(readStringList(json)));
    }

    Set<WorkerCapability> readCapabilities(String json) {
        return readStringList(json).stream().map(WorkerCapability::new).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    WorkerCapabilities readWorkerCapabilities(String json) {
        return new WorkerCapabilities(readCapabilities(json));
    }
}
