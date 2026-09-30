package io.github.flowerjvm.factory.infrastructure.referenceassembly;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyArtifactCodec;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyInspectionReport;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseSubject;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/** Strict canonical JSON codec for the concrete Reference Assembly artifacts. */
public final class JacksonReferenceAssemblyArtifactCodec implements ReferenceAssemblyArtifactCodec {
    public static final int MAX_ARTIFACT_BYTES = 1024 * 1024;

    private final ObjectMapper mapper;

    public JacksonReferenceAssemblyArtifactCodec() {
        this(new ObjectMapper());
    }

    public JacksonReferenceAssemblyArtifactCodec(ObjectMapper mapper) {
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
    public byte[] writeRequirement(ReferenceAssemblyRequirement value) {
        return write(value);
    }

    @Override
    public ReferenceAssemblyRequirement readRequirement(byte[] content) {
        return readCanonical(content, ReferenceAssemblyRequirement.class, this::writeRequirement);
    }

    @Override
    public byte[] writeConsumerContract(ReferenceAssemblyConsumerContract value) {
        return write(value);
    }

    @Override
    public ReferenceAssemblyConsumerContract readConsumerContract(byte[] content) {
        return readCanonical(
                content, ReferenceAssemblyConsumerContract.class, this::writeConsumerContract);
    }

    @Override
    public byte[] writeManifest(ReferenceAssemblyManifest value) {
        return write(value);
    }

    @Override
    public ReferenceAssemblyManifest readManifest(byte[] content) {
        return readCanonical(content, ReferenceAssemblyManifest.class, this::writeManifest);
    }

    @Override
    public byte[] writeInspectionReport(ReferenceAssemblyInspectionReport value) {
        return write(value);
    }

    @Override
    public ReferenceAssemblyInspectionReport readInspectionReport(byte[] content) {
        return readCanonical(
                content, ReferenceAssemblyInspectionReport.class, this::writeInspectionReport);
    }

    @Override
    public byte[] writeReleaseSubject(ReferenceAssemblyReleaseSubject value) {
        return write(value);
    }

    @Override
    public ReferenceAssemblyReleaseSubject readReleaseSubject(byte[] content) {
        return readCanonical(
                content, ReferenceAssemblyReleaseSubject.class, this::writeReleaseSubject);
    }

    @Override
    public byte[] writeReleaseManifest(ReferenceAssemblyReleaseManifest value) {
        return write(value);
    }

    @Override
    public ReferenceAssemblyReleaseManifest readReleaseManifest(byte[] content) {
        return readCanonical(
                content, ReferenceAssemblyReleaseManifest.class, this::writeReleaseManifest);
    }

    private byte[] write(Object value) {
        Objects.requireNonNull(value, "value");
        try {
            byte[] content = mapper.writeValueAsBytes(value);
            requireBounded(content);
            return content;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Reference Assembly artifact JSON serialization failed", exception);
        }
    }

    private <T> T readCanonical(byte[] content, Class<T> type, Function<T, byte[]> writer) {
        requireBounded(content);
        try {
            T value = mapper.readValue(content, type);
            if (!Arrays.equals(content, writer.apply(value))) {
                throw new IllegalArgumentException(
                        "Reference Assembly artifact is valid JSON but not canonical JSON");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Reference Assembly artifact is not strict JSON", exception);
        }
    }

    private static void requireBounded(byte[] content) {
        Objects.requireNonNull(content, "content");
        if (content.length == 0 || content.length > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException(
                    "Reference Assembly artifact JSON must be non-empty and bounded");
        }
    }
}
