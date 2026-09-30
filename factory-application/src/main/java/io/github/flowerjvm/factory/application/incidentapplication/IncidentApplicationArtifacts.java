package io.github.flowerjvm.factory.application.incidentapplication;

import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded canonical JSON for authority-bearing line-owned documents; no arbitrary object serialization. */
public final class IncidentApplicationArtifacts {
    private IncidentApplicationArtifacts() { }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String hash(String text) { return hash(text.getBytes(StandardCharsets.UTF_8)); }
    public static CertificationArtifactLock lock(Artifact artifact) { return new CertificationArtifactLock(artifact.reference(), artifact.contentHash()); }
    public static Map<String,Object> lockMap(CertificationArtifactLock lock) { return Map.of("ref", lock.reference().value(), "sha256", lock.hash().sha256()); }
    public static Artifact document(TenantId tenant, String kind, Map<String,Object> fields) {
        var values = new TreeMap<>(fields); values.put("schemaVersion", "factory.incident-application." + kind + ".v1");
        byte[] bytes = canonical(values);
        if (bytes.length > 256 * 1024) throw IncidentApplicationOrder.invalid();
        String hash = hash(bytes);
        return new Artifact(tenant, new ArtifactReference("factory-incident-application/" + kind + "/sha256/" + hash),
                new ContentHash(hash), "application/json", bytes);
    }
    public static byte[] canonical(Object value) { return (json(value) + "\n").getBytes(StandardCharsets.UTF_8); }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) return value.toString();
        if (value instanceof String text) {
            var out = new StringBuilder("\"");
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c < 32 || Character.isSurrogate(c)) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                else out.append(c);
            }
            return out.append('"').toString();
        }
        if (value instanceof Map<?,?> map) {
            var ordered = new TreeMap<String,Object>();
            for (var entry : map.entrySet()) { if (!(entry.getKey() instanceof String key)) throw IncidentApplicationOrder.invalid(); ordered.put(key, entry.getValue()); }
            var parts = new ArrayList<String>(); ordered.forEach((key,item) -> parts.add(json(key) + ":" + json(item)));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(IncidentApplicationArtifacts::json).toList()) + "]";
        throw IncidentApplicationOrder.invalid();
    }
    public static Artifact require(ArtifactStore store, TenantId tenant, CertificationArtifactLock lock) {
        var artifact = store.find(tenant, lock.reference()).orElseThrow(IncidentApplicationOrder::invalid);
        if (!tenant.equals(artifact.tenantId()) || !artifact.reference().equals(lock.reference())
                || !artifact.contentHash().equals(lock.hash()) || !hash(artifact.content()).equals(lock.hash().sha256())) throw IncidentApplicationOrder.invalid();
        return artifact;
    }
}
