package io.github.flowerjvm.factory.infrastructure.incidentapplication.tool;

import static org.junit.jupiter.api.Assertions.*;
import io.github.flowerjvm.factory.application.incidentapplication.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.*;
import io.github.flowerjvm.factory.contracts.ids.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncidentApplicationToolAuthorityTest {
    @TempDir Path root;
    private static final CertificationArtifactLock LOCK = new CertificationArtifactLock(new ArtifactReference("fixture-lock"), new ContentHash("a".repeat(64)));
    private IncidentApplicationOrder order() {
        var component = new CertifiedAgentComponentRef(CertifiedAgentComponentRef.SCHEMA_VERSION, "embedded-agent-pack",
                ProductLineId.AGENT_PACK, CertifiedArtifactType.AGENT_PACK, new CertificationId("fixture-cert"), LOCK,
                new CandidateId("fixture-candidate"), new ContentHash("b".repeat(64)), LOCK, LOCK,
                new VerificationRunId("fixture-verification"), LOCK, LOCK, LOCK, "fixture-profile");
        return new IncidentApplicationOrder(new BuildSessionId("fixture-session"), new TenantId("fixture-tenant"),
                new ProjectId("fixture-project"), "fixture-request", IncidentApplicationOrder.Variant.BASIC, component,
                Instant.parse("2030-01-01T00:00:00Z"));
    }
    @Test void constructorAndFullGateDenialsDoNotTouchFilesystemOrRunExternalEquipment() {
        var resolutions = new AtomicInteger(); var reads = new AtomicInteger(); var writes = new AtomicInteger();
        var store = new ArtifactStore() {
            @Override public ArtifactReference store(Artifact artifact) { writes.incrementAndGet(); throw new AssertionError("denied input cannot stage artifacts"); }
            @Override public Optional<Artifact> find(TenantId tenant, ArtifactReference reference) { reads.incrementAndGet(); throw new AssertionError("denied input cannot read product graph"); }
        };
        Path nonexistent = root.resolve("must-not-be-created");
        var tool = new DockerIncidentApplicationProductionTool(store, (tenant, component) -> {
            resolutions.incrementAndGet(); assertEquals(order().tenantId(), tenant); assertEquals(order().component(), component);
            throw new IllegalStateException("CURRENT_COMPONENT_REVOKED");
        }, nonexistent.resolve("catalog"), nonexistent.resolve("repository"), nonexistent.resolve("work"), "missing-docker-must-not-run", Duration.ofSeconds(30));
        assertFalse(Files.exists(nonexistent));
        var work = new IncidentApplicationBuildWorkOrder(order(), LOCK, LOCK, LOCK, LOCK, LOCK, DockerIncidentApplicationProductionTool.ALGORITHM);
        var product = new IncidentApplicationPreparedProduct(LOCK, LOCK, LOCK, LOCK);
        var verification = new IncidentApplicationWholeVerification(true, "INCIDENT_APPLICATION_WHOLE_PRODUCT_PASSED", LOCK, LOCK);
        assertEquals("CURRENT_COMPONENT_REVOKED", assertThrows(IllegalStateException.class, () -> tool.plan(order())).getMessage());
        assertEquals("CURRENT_COMPONENT_REVOKED", assertThrows(IllegalStateException.class, () -> tool.produce(work)).getMessage());
        assertEquals("CURRENT_COMPONENT_REVOKED", assertThrows(IllegalStateException.class, () -> tool.verify(work, product)).getMessage());
        assertEquals("CURRENT_COMPONENT_REVOKED", assertThrows(IllegalStateException.class, () -> tool.validate(work, product, verification)).getMessage());
        assertEquals(4, resolutions.get()); assertEquals(0, reads.get()); assertEquals(0, writes.get()); assertFalse(Files.exists(nonexistent));
    }
    @Test void unsupportedAlgorithmCannotSelectAnAlternativeBuilder() {
        var tool = new DockerIncidentApplicationProductionTool(new ArtifactStore() {
            @Override public ArtifactReference store(Artifact artifact) { throw new AssertionError(); }
            @Override public Optional<Artifact> find(TenantId tenant, ArtifactReference ref) { throw new AssertionError(); }
        }, (tenant, component) -> { throw new AssertionError(); }, root, root, root.resolve("work"), "missing", Duration.ofSeconds(30));
        var work = new IncidentApplicationBuildWorkOrder(order(), LOCK, LOCK, LOCK, LOCK, LOCK, "untrusted-builder.v1");
        assertThrows(IllegalStateException.class, () -> tool.produce(work)); assertFalse(Files.exists(root.resolve("work")));
    }
}
