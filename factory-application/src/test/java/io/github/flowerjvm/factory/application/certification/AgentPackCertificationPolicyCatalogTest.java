package io.github.flowerjvm.factory.application.certification;

import static org.junit.jupiter.api.Assertions.*;

import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.verification.ActionBackedVerificationRunLauncher;
import io.github.flowerjvm.factory.application.work.WorkOrderRepository;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifactDecoder;
import io.github.flowerjvm.factory.application.work.WorkerProtocolArtifacts;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.*;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.contracts.worker.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentPackCertificationPolicyCatalogTest {
    private static final TenantId TENANT = new TenantId("catalog-tenant");
    private static final BuildSessionId SESSION = new BuildSessionId("catalog-session");
    private static final WorkOrderId ORDER = new WorkOrderId("catalog-order");
    private static final ContentHash FIXTURES = hash("fixtures");
    private static final String PR4 = ActionBackedVerificationRunLauncher.GATE_PROFILE;
    private static final String MAINTENANCE = MaintenanceInvestigationProductContract.GATE_PROFILE;
    private static final CertificationArtifactLock API = MaintenanceInvestigationProductContract.apiSignatureIndexLock();
    private static final CertificationArtifactLock MATRIX = MaintenanceInvestigationProductContract.requirementTestMatrixLock();

    @Test
    void twoProfilesRemainSeparateAndLegacyCanonicalContractIsUnchanged() {
        Fixture f = new Fixture();
        var legacy = f.catalog.policyForGeneration(input(AgentPackProductContract.lock(), PR4, API, MATRIX));
        var maintenance = f.catalog.policyForGeneration(f.input);
        assertEquals(PR4, legacy.gateProfile());
        assertEquals(MAINTENANCE, maintenance.gateProfile());
        assertEquals("internal", legacy.certificationProfile());
        assertEquals("internal", maintenance.certificationProfile());
        assertNotEquals(legacy.productContractBundle(), maintenance.productContractBundle());
        assertEquals("c646e0a6939f1709697aafdfe0ec431d6ec105a2819731ef670bd267a83d8edf",
                AgentPackProductContract.lock().hash().sha256());
        assertTrue(f.catalog.matches(f.lock()));
        f.input = input(AgentPackProductContract.lock(), PR4, API, MATRIX);
        assertTrue(f.catalog.matches(f.lock()));
    }

    @Test
    void contractAndGateCannotBeCrossedOrSelectedByAnUnrecognizedHash() {
        Fixture f = new Fixture();
        for (var input : List.of(input(AgentPackProductContract.lock(), MAINTENANCE, API, MATRIX),
                input(MaintenanceInvestigationProductContract.lock(), PR4, API, MATRIX),
                input(artifactLock("unrecognized"), MAINTENANCE, API, MATRIX))) {
            assertThrows(IllegalArgumentException.class, () -> f.catalog.policyForGeneration(input));
        }
    }

    @Test
    void maintenanceApiAndMatrixRequireBothExactReferenceAndHash() {
        Fixture f = new Fixture();
        for (var other : List.of(artifactLock("other"),
                new CertificationArtifactLock(new ArtifactReference("different-ref"), API.hash()),
                new CertificationArtifactLock(API.reference(), hash("different-bytes")))) {
            assertThrows(IllegalArgumentException.class, () -> f.catalog.policyForGeneration(
                    input(MaintenanceInvestigationProductContract.lock(), MAINTENANCE, other, MATRIX)));
        }
        for (var other : List.of(artifactLock("other"),
                new CertificationArtifactLock(new ArtifactReference("different-ref"), MATRIX.hash()),
                new CertificationArtifactLock(MATRIX.reference(), hash("different-bytes")))) {
            assertThrows(IllegalArgumentException.class, () -> f.catalog.policyForGeneration(
                    input(MaintenanceInvestigationProductContract.lock(), MAINTENANCE, API, other)));
        }
    }

    @Test
    void singletonCompatibilityCannotAuthorizeTheNewProfileWithoutProvenance() {
        Fixture f = new Fixture();
        assertThrows(IllegalArgumentException.class,
                () -> AgentPackCertificationPolicyCatalog.singleton(f.catalog.policyForGeneration(f.input)));
        f.input = input(AgentPackProductContract.lock(), PR4, API, MATRIX);
        var policy = f.catalog.policyForGeneration(f.input);
        assertTrue(AgentPackCertificationPolicyCatalog.singleton(policy).matches(f.lock()));
    }

    @Test
    void generationProvenanceIsRecheckedAndNeverCachedAfterAValidObservation() {
        Fixture f = new Fixture();
        CertificationInputLock lock = f.lock();
        assertTrue(f.catalog.matches(lock));
        f.input = input(MaintenanceInvestigationProductContract.lock(), MAINTENANCE, API, artifactLock("matrix-drift"));
        assertFalse(f.catalog.matches(lock));
        f.input = input(MaintenanceInvestigationProductContract.lock(), MAINTENANCE, API, MATRIX);
        assertTrue(f.catalog.matches(lock));
        f.orderPresent = false;
        assertFalse(f.catalog.matches(lock));
    }

    @Test
    void missingOrTamperedMatrixBytesAreRejectedDespitePersistedExpectedHash() {
        Fixture f = new Fixture();
        CertificationInputLock lock = f.lock();
        f.values.remove(MATRIX.reference());
        assertFalse(f.catalog.matches(lock));
        f.values.put(MATRIX.reference(), new Artifact(TENANT, MATRIX.reference(), MATRIX.hash(),
                "application/json", "corrupt bytes".getBytes(StandardCharsets.UTF_8)));
        assertFalse(f.catalog.matches(lock));
    }

    @Test
    void differentGenerationOwnerOrTenantAndCorruptInputArtifactFailClosed() {
        Fixture f = new Fixture();
        CertificationInputLock lock = f.lock();
        f.order = f.order(new TenantId("other-tenant"), BuildSessionPhase.GENERATE_CANDIDATE.id());
        assertFalse(f.catalog.matches(lock));
        f.order = f.order(TENANT, BuildSessionPhase.CERTIFY.id());
        assertFalse(f.catalog.matches(lock));
        f.order = f.order(TENANT, BuildSessionPhase.GENERATE_CANDIDATE.id());
        f.values.put(f.generation.reference(), new Artifact(TENANT, f.generation.reference(), f.generation.hash(),
                "application/json", new byte[] { 1 }));
        assertFalse(f.catalog.matches(lock));
    }

    @Test
    void persistedLockMustSelectTheSameExactPolicyAndGeneration() {
        Fixture f = new Fixture();
        CertificationInputLock lock = f.lock();
        assertTrue(f.catalog.matchesGeneration(lock, f.input));
        assertFalse(f.catalog.matchesGeneration(lock, input(AgentPackProductContract.lock(), PR4, API, MATRIX)));
        assertFalse(f.catalog.matchesGeneration(lock, null));
        assertFalse(f.catalog.admits(null));
        assertFalse(f.catalog.matches(null));
    }

    private static CodingWorkerInputManifest input(
            CertificationArtifactLock product, String gate, CertificationArtifactLock api, CertificationArtifactLock matrix) {
        return new CodingWorkerInputManifest(CodingWorkerInputManifest.SCHEMA_VERSION, ORDER, SESSION,
                "builder", "1.0.0", artifactLock("skill").reference(), hash("skill"),
                artifactLock("dependency").reference(), hash("dependency"),
                artifactLock("toolchain").reference(), hash("toolchain"), api.reference(), api.hash(),
                product.reference(), product.hash(), gate, matrix.reference(), matrix.hash(),
                CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID, Optional.empty());
    }

    private static final class Fixture {
        final Map<ArtifactReference, Artifact> values = new HashMap<>();
        final CertificationArtifactLock generation = artifactLock("generation");
        CodingWorkerInputManifest input = input(MaintenanceInvestigationProductContract.lock(), MAINTENANCE, API, MATRIX);
        WorkOrder order = order(TENANT, BuildSessionPhase.GENERATE_CANDIDATE.id());
        boolean orderPresent = true;
        final AgentPackCertificationPolicyCatalog catalog;

        Fixture() {
            values.put(generation.reference(), new Artifact(TENANT, generation.reference(), generation.hash(),
                    "application/json", "generation".getBytes(StandardCharsets.UTF_8)));
            MaintenanceInvestigationProductContract.artifacts(TENANT).forEach(a -> values.put(a.reference(), a));
            ArtifactStore store = new ArtifactStore() {
                @Override public ArtifactReference store(Artifact artifact) { throw new UnsupportedOperationException(); }
                @Override public Optional<Artifact> find(TenantId tenant, ArtifactReference reference) {
                    return Optional.ofNullable(values.get(reference)).filter(a -> a.tenantId().equals(tenant));
                }
            };
            WorkOrderRepository orders = new WorkOrderRepository() {
                @Override public void create(WorkOrder value) { throw new UnsupportedOperationException(); }
                @Override public Optional<WorkOrder> find(TenantId tenant, WorkOrderId id) {
                    return orderPresent ? Optional.of(order) : Optional.empty();
                }
            };
            WorkerProtocolArtifacts protocol = new WorkerProtocolArtifacts(store, new WorkerProtocolArtifactDecoder() {
                @Override public CodingWorkerInputManifest decodeInputManifest(byte[] bytes) { return input; }
                @Override public CodingWorkerCompletionPayload decodeCompletionPayload(byte[] bytes) { throw new UnsupportedOperationException(); }
                @Override public CandidateSourceManifest decodeCandidateSourceManifest(byte[] bytes) { throw new UnsupportedOperationException(); }
            });
            catalog = AgentPackCertificationPolicyCatalog.production(FIXTURES, "0.1.0-internal.1", "0.1.3", "0.3.3", orders, protocol);
        }

        CertificationInputLock lock() {
            var policy = catalog.policyForGeneration(input);
            return new CertificationInputLock(CertificationInputLock.SCHEMA_VERSION, TENANT,
                    ProductLineId.AGENT_PACK, CertifiedArtifactType.AGENT_PACK, SESSION, ORDER,
                    new CandidateId("catalog-candidate"), hash("candidate"), artifactLock("source"),
                    artifactLock("dependency"), artifactLock("toolchain"), generation, policy.productContractBundle(),
                    new CertificationArtifactLock(input.apiSignatureIndexRef(), input.apiSignatureIndexHash()),
                    policy.sourceLockAlgorithmId(), policy.gateProfile(), new VerificationRunId("catalog-verification"),
                    "catalog-verification-action", artifactLock("verification-result"), FIXTURES,
                    artifactLock("policy"), artifactLock("compatibility"), policy.certificationProfile(),
                    policy.factoryVersion(), policy.flowerVersion(), policy.actionRuntimeVersion());
        }

        WorkOrder order(TenantId tenant, String phase) {
            return new WorkOrder(ORDER, tenant, SESSION, phase, "generate", 1, Optional.empty(), Optional.empty(),
                    Optional.of("base"), artifactLock("instruction").reference(), hash("instruction"),
                    generation.reference(), generation.hash(), "workspace", List.of("src"), List.of("src"),
                    Set.of(), "output", "1", artifactLock("policy").reference(),
                    Instant.parse("2026-09-06T12:00:00Z"), 1, "key", WorkOrderCreatorType.SYSTEM,
                    "flow", Instant.parse("2026-09-06T11:00:00Z"));
        }
    }

    private static CertificationArtifactLock artifactLock(String value) {
        return new CertificationArtifactLock(new ArtifactReference("artifact:" + value), hash(value));
    }

    private static ContentHash hash(String value) {
        try {
            return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception impossible) { throw new AssertionError(impossible); }
    }
}
