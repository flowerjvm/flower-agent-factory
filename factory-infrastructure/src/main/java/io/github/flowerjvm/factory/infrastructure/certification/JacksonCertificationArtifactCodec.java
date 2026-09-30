package io.github.flowerjvm.factory.infrastructure.certification;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.flowerjvm.factory.application.certification.CertificationArtifactCodec;
import io.github.flowerjvm.factory.contracts.certification.AgentPackCompatibilityDescriptor;
import io.github.flowerjvm.factory.contracts.certification.CertificationEvidenceManifest;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentManifest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/** Strict canonical JSON codec for the four stored PR6-A certification artifacts. */
public final class JacksonCertificationArtifactCodec implements CertificationArtifactCodec {
    public static final String MEDIA_TYPE = "application/json";
    public static final int MAX_ARTIFACT_BYTES = 1024 * 1024;

    private final ObjectMapper mapper;

    public JacksonCertificationArtifactCodec() {
        this(new ObjectMapper());
    }

    public JacksonCertificationArtifactCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper")
                .copy()
                .findAndRegisterModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public byte[] writeInputLock(CertificationInputLock value) {
        return write(value);
    }

    @Override
    public CertificationInputLock readInputLock(byte[] content) {
        return readCanonical(content, CertificationInputLock.class, this::writeInputLock);
    }

    @Override
    public byte[] writeEvidence(CertificationEvidenceManifest value) {
        return write(value);
    }

    @Override
    public CertificationEvidenceManifest readEvidence(byte[] content) {
        return readCanonical(content, CertificationEvidenceManifest.class, this::writeEvidence);
    }

    @Override
    public byte[] writeComponentManifest(CertifiedAgentComponentManifest value) {
        return write(value);
    }

    @Override
    public CertifiedAgentComponentManifest readComponentManifest(byte[] content) {
        return readCanonical(content, CertifiedAgentComponentManifest.class, this::writeComponentManifest);
    }

    @Override
    public byte[] writeCompatibilityDescriptor(AgentPackCompatibilityDescriptor value) {
        return write(value);
    }

    @Override
    public AgentPackCompatibilityDescriptor readCompatibilityDescriptor(byte[] content) {
        return readCanonical(content, AgentPackCompatibilityDescriptor.class, this::writeCompatibilityDescriptor);
    }

    private byte[] write(Object value) {
        Objects.requireNonNull(value, "value");
        try {
            byte[] content = mapper.writeValueAsBytes(value);
            requireBounded(content);
            return content;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("certification artifact JSON serialization failed", exception);
        }
    }

    private <T> T readCanonical(byte[] content, Class<T> type, Function<T, byte[]> writer) {
        requireBounded(content);
        try {
            T value = mapper.readValue(content, type);
            if (!Arrays.equals(content, writer.apply(value))) {
                throw new IllegalArgumentException("certification artifact is valid JSON but not canonical JSON");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalArgumentException("certification artifact is not strict JSON", exception);
        }
    }

    private static void requireBounded(byte[] content) {
        Objects.requireNonNull(content, "content");
        if (content.length == 0 || content.length > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("certification artifact JSON must be non-empty and bounded");
        }
    }
}
