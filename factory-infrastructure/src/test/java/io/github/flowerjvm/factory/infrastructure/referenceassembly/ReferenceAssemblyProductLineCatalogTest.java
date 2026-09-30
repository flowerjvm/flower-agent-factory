package io.github.flowerjvm.factory.infrastructure.referenceassembly;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.certification.AgentPackProductContract;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyAdmissionPolicies;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyAdmissionPolicy;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyArtifactCodec;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyException;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyProductLineCatalog;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.infrastructure.persistence.FactoryDatabaseMigrations;
import io.github.flowerjvm.factory.infrastructure.persistence.JdbcArtifactStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class ReferenceAssemblyProductLineCatalogTest {
    private static final TenantId TENANT_A = new TenantId("tenant-reference-catalog-a");
    private static final TenantId TENANT_B = new TenantId("tenant-reference-catalog-b");

    @Test
    void explicitLegacyEntryPreservesDefaultLocksBytesAndRequirementIdentity() {
        Fixture fixture = fixture("legacy_entry_compatibility");
        var catalog = fixture.catalog();
        var legacy = ReferenceAssemblyProductLineCatalog.Entry.LEGACY_PR4;
        assertEquals(catalog.admissionPolicy(), catalog.admissionPolicy(legacy));
        assertEquals(catalog.consumerContractLock(), catalog.consumerContractLock(legacy));
        assertEquals(catalog.expectedApiSignatureIndex(), catalog.expectedApiSignatureIndex(legacy));
        assertEquals(catalog.hostFixtureLock(), catalog.hostFixtureLock(legacy));
        assertEquals(catalog.policySnapshotLock(), catalog.policySnapshotLock(legacy));
        var original = catalog.stageRequirement(TENANT_A, component());
        byte[] originalBytes = fixture.store().find(TENANT_A, original.reference()).orElseThrow().content();
        catalog.provisionTenant(TENANT_A, ReferenceAssemblyProductLineCatalog.Entry.MAINTENANCE_INVESTIGATION_V1);
        var explicit = catalog.stageRequirement(TENANT_A, component(), legacy);
        assertEquals(original, explicit);
        assertArrayEquals(originalBytes, fixture.store().find(TENANT_A, explicit.reference()).orElseThrow().content());
        var decoded = fixture.codec().readConsumerContract(fixture.store()
                .find(TENANT_A, catalog.consumerContractLock().reference()).orElseThrow().content());
        assertEquals("1.0.0", decoded.contractVersion());
    }

    @Test
    void maintenanceEntryPinsVersionedConsumerPolicyAndActualProductApiWhileReusingMetadataHost() throws Exception {
        Fixture fixture = fixture("maintenance_entry");
        var catalog = fixture.catalog();
        var entry = ReferenceAssemblyProductLineCatalog.Entry.MAINTENANCE_INVESTIGATION_V1;
        var requirement = catalog.stageRequirement(TENANT_A, component(), entry);
        assertEquals(requirement, catalog.stageRequirement(TENANT_A, component(), entry));
        assertFalse(fixture.store().find(TENANT_B, requirement.reference()).isPresent());
        assertEquals(requirement, catalog.stageRequirement(TENANT_B, component(), entry));
        var policy = catalog.admissionPolicy(entry);
        assertNotEquals(catalog.consumerContractLock(), policy.approvedConsumerContract());
        assertNotEquals(catalog.policySnapshotLock(), policy.approvedPolicySnapshot());
        assertEquals(catalog.hostFixtureLock(), policy.approvedHostFixture());
        assertEquals(MaintenanceInvestigationProductContract.lock(), policy.expectedAgentProductContract());
        assertEquals(MaintenanceInvestigationProductContract.apiSignatureIndexLock(), policy.expectedApiSignatureIndex());
        assertEquals("internal", policy.requiredCertificationProfile());
        var contractArtifact = fixture.store().find(TENANT_A, policy.approvedConsumerContract().reference()).orElseThrow();
        var contract = fixture.codec().readConsumerContract(contractArtifact.content());
        assertEquals("2.0.0", contract.contractVersion());
        assertEquals(policy.expectedAgentProductContract(), contract.requiredAgentProductContract());
        assertEquals(policy.expectedApiSignatureIndex(), contract.requiredApiSignatureIndex());
        assertArrayEquals(contractArtifact.content(), fixture.codec().writeConsumerContract(contract));
        var snapshot = new com.fasterxml.jackson.databind.ObjectMapper().readTree(fixture.store()
                .find(TENANT_A, policy.approvedPolicySnapshot().reference()).orElseThrow().content());
        assertEquals("2.0.0", snapshot.path("policyVersion").asText());
        assertEquals(MaintenanceInvestigationProductContract.GATE_PROFILE, snapshot.path("gateProfile").asText());
        assertEquals(policy.expectedAgentProductContract().hash().sha256(), snapshot.path("productContractHash").asText());
        assertEquals(policy.expectedApiSignatureIndex().hash().sha256(), snapshot.path("apiSignatureIndexHash").asText());
    }

    @Test
    void catalogLookupRequiresBothExactReferenceAndHashAndRejectsDuplicateEntries() {
        var catalog = fixture("exact_entry_lookup").catalog();
        var entry = ReferenceAssemblyProductLineCatalog.Entry.MAINTENANCE_INVESTIGATION_V1;
        var selected = catalog.admissionPolicy(entry);
        assertEquals(selected, catalog.admissionPolicies().find(selected.approvedConsumerContract()).orElseThrow());
        assertEquals(catalog.admissionPolicy(), catalog.admissionPolicies().find(catalog.consumerContractLock()).orElseThrow());
        assertTrue(catalog.admissionPolicies().find(new CertificationArtifactLock(
                catalog.consumerContractLock().reference(), selected.approvedConsumerContract().hash())).isEmpty());
        assertTrue(catalog.admissionPolicies().find(new CertificationArtifactLock(
                selected.approvedConsumerContract().reference(), catalog.consumerContractLock().hash())).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ReferenceAssemblyAdmissionPolicies(List.of(selected, selected)));
        assertThrows(IllegalArgumentException.class, () -> new ReferenceAssemblyAdmissionPolicies(List.of()));
    }

    @Test
    void provisioningIsDeterministicAndIdempotentPerTenant() throws Exception {
        Fixture fixture = fixture("provisioning");
        var first = fixture.catalog();
        var independentlyConstructed = new ReferenceAssemblyProductLineCatalog(
                fixture.store(), new JacksonReferenceAssemblyArtifactCodec());

        assertEquals(first.consumerContractLock(), independentlyConstructed.consumerContractLock());
        assertEquals(first.expectedApiSignatureIndex(), independentlyConstructed.expectedApiSignatureIndex());
        assertEquals(first.hostFixtureLock(), independentlyConstructed.hostFixtureLock());
        assertEquals(first.policySnapshotLock(), independentlyConstructed.policySnapshotLock());

        first.provisionTenant(TENANT_A);
        first.provisionTenant(TENANT_A);
        independentlyConstructed.provisionTenant(TENANT_A);
        independentlyConstructed.provisionTenant(TENANT_B);
        independentlyConstructed.provisionTenant(TENANT_B);

        assertEquals(5, artifactCount(fixture.dataSource(), TENANT_A));
        assertEquals(5, artifactCount(fixture.dataSource(), TENANT_B));
        for (CertificationArtifactLock lock : fixtureLocks(first)) {
            Artifact tenantA = fixture.store().find(TENANT_A, lock.reference()).orElseThrow();
            Artifact tenantB = fixture.store().find(TENANT_B, lock.reference()).orElseThrow();
            assertEquals(lock.hash(), tenantA.contentHash());
            assertEquals(lock.hash(), tenantB.contentHash());
            assertArrayEquals(tenantA.content(), tenantB.content());
        }
    }

    @Test
    void stagesOneContentAddressedCanonicalRequirementForTheExactComponent() throws Exception {
        Fixture fixture = fixture("requirement");
        CertifiedAgentComponentRef component = component();

        CertificationArtifactLock first = fixture.catalog().stageRequirement(TENANT_A, component);
        CertificationArtifactLock repeated = fixture.catalog().stageRequirement(TENANT_A, component);
        CertificationArtifactLock reconstructed = new ReferenceAssemblyProductLineCatalog(
                        fixture.store(), new JacksonReferenceAssemblyArtifactCodec())
                .stageRequirement(TENANT_A, component);

        assertEquals(first, repeated);
        assertEquals(first, reconstructed);
        assertEquals(
                "factory-reference-assembly/requirement/sha256/" + first.hash().sha256(),
                first.reference().value());
        assertEquals(6, artifactCount(fixture.dataSource(), TENANT_A));

        Artifact stored = fixture.store().find(TENANT_A, first.reference()).orElseThrow();
        assertEquals(first.hash(), stored.contentHash());
        assertEquals(sha256(stored.content()), first.hash());
        assertEquals(ReferenceAssemblyArtifactCodec.MEDIA_TYPE, stored.mediaType());

        ReferenceAssemblyRequirement decoded = fixture.codec().readRequirement(stored.content());
        assertEquals(ReferenceAssemblyRequirement.SCHEMA_VERSION, decoded.schemaVersion());
        assertEquals(ProductLineId.REFERENCE_ASSEMBLY, decoded.productLineId());
        assertEquals(fixture.catalog().consumerContractLock(), decoded.consumerContract());
        assertEquals(fixture.catalog().hostFixtureLock(), decoded.hostFixture());
        assertEquals(fixture.catalog().policySnapshotLock(), decoded.policySnapshot());
        assertEquals(component, decoded.component());
        assertArrayEquals(stored.content(), fixture.codec().writeRequirement(decoded));
    }

    @Test
    void admissionPolicyAndConsumerContractUseTheSameExactCatalogLocks() {
        Fixture fixture = fixture("admission_policy");
        fixture.catalog().provisionTenant(TENANT_A);
        ReferenceAssemblyAdmissionPolicy policy = fixture.catalog().admissionPolicy();

        assertEquals(fixture.catalog().consumerContractLock(), policy.approvedConsumerContract());
        assertEquals(fixture.catalog().hostFixtureLock(), policy.approvedHostFixture());
        assertEquals(fixture.catalog().policySnapshotLock(), policy.approvedPolicySnapshot());
        assertEquals(AgentPackProductContract.lock(), policy.expectedAgentProductContract());
        assertEquals(fixture.catalog().expectedApiSignatureIndex(), policy.expectedApiSignatureIndex());
        assertEquals(ReferenceAssemblyRequirement.COMPONENT_ROLE, policy.requiredComponentRole());
        assertEquals(
                ReferenceAssemblyConsumerContract.REQUIRED_CERTIFICATION_PROFILE,
                policy.requiredCertificationProfile());
        assertEquals(
                ReferenceAssemblyConsumerContract.REQUIRED_FLOWER_VERSION,
                policy.requiredFlowerVersion());
        assertEquals(
                ReferenceAssemblyConsumerContract.REQUIRED_ACTION_RUNTIME_VERSION,
                policy.requiredActionRuntimeVersion());
        assertEquals(
                CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                policy.requiredSourceLockAlgorithmId());
        assertEquals(
                ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID,
                policy.assemblyAlgorithmId());

        Artifact contractArtifact = fixture.store()
                .find(TENANT_A, fixture.catalog().consumerContractLock().reference())
                .orElseThrow();
        ReferenceAssemblyConsumerContract contract =
                fixture.codec().readConsumerContract(contractArtifact.content());
        assertEquals(policy.expectedAgentProductContract(), contract.requiredAgentProductContract());
        assertEquals(policy.expectedApiSignatureIndex(), contract.requiredApiSignatureIndex());
        assertEquals(policy.approvedHostFixture(), contract.requiredHostFixture());
        assertEquals(policy.requiredComponentRole(), contract.requiredComponentRole());
        assertEquals(policy.requiredCertificationProfile(), contract.requiredCertificationProfile());
        assertEquals(policy.requiredFlowerVersion(), contract.requiredFlowerVersion());
        assertEquals(policy.requiredActionRuntimeVersion(), contract.requiredActionRuntimeVersion());
        assertEquals(policy.assemblyAlgorithmId(), contract.assemblyAlgorithmId());
        assertArrayEquals(
                contractArtifact.content(), fixture.codec().writeConsumerContract(contract));
    }

    @Test
    void tenantScopedCopiesAreIsolatedAndTamperingFailsClosedOnlyForTheAffectedTenant()
            throws Exception {
        Fixture fixture = fixture("tenant_isolation");
        CertifiedAgentComponentRef component = component();

        CertificationArtifactLock tenantARequirement =
                fixture.catalog().stageRequirement(TENANT_A, component);
        assertFalse(fixture.store().find(TENANT_B, tenantARequirement.reference()).isPresent());

        CertificationArtifactLock tenantBRequirement =
                fixture.catalog().stageRequirement(TENANT_B, component);
        assertEquals(tenantARequirement, tenantBRequirement);
        assertTrue(fixture.store().find(TENANT_A, tenantARequirement.reference()).isPresent());
        assertTrue(fixture.store().find(TENANT_B, tenantBRequirement.reference()).isPresent());

        changeMediaType(
                fixture.dataSource(),
                TENANT_A,
                fixture.catalog().consumerContractLock().reference(),
                "text/plain");

        ReferenceAssemblyException rejected = assertThrows(
                ReferenceAssemblyException.class,
                () -> fixture.catalog().provisionTenant(TENANT_A));
        assertEquals(ReferenceAssemblyException.STAGING_CONFLICT, rejected.code());
        assertDoesNotThrow(() -> fixture.catalog().provisionTenant(TENANT_B));
        assertEquals(
                ReferenceAssemblyArtifactCodec.MEDIA_TYPE,
                fixture.store()
                        .find(TENANT_B, fixture.catalog().consumerContractLock().reference())
                        .orElseThrow()
                        .mediaType());
    }

    private static Fixture fixture(String databaseName) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:factory_reference_catalog_" + databaseName
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        FactoryDatabaseMigrations.migrate(dataSource);
        var store = new JdbcArtifactStore(dataSource);
        var codec = new JacksonReferenceAssemblyArtifactCodec();
        return new Fixture(
                dataSource,
                store,
                codec,
                new ReferenceAssemblyProductLineCatalog(store, codec));
    }

    private static List<CertificationArtifactLock> fixtureLocks(
            ReferenceAssemblyProductLineCatalog catalog) {
        return List.of(
                AgentPackProductContract.lock(),
                catalog.expectedApiSignatureIndex(),
                catalog.hostFixtureLock(),
                catalog.policySnapshotLock(),
                catalog.consumerContractLock());
    }

    private static int artifactCount(JdbcDataSource dataSource, TenantId tenantId)
            throws Exception {
        try (var connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM factory_artifact WHERE tenant_id = ?")) {
            statement.setString(1, tenantId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getInt(1);
            }
        }
    }

    private static void changeMediaType(
            JdbcDataSource dataSource,
            TenantId tenantId,
            ArtifactReference reference,
            String mediaType)
            throws Exception {
        try (var connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE factory_artifact
                        SET media_type = ?
                        WHERE tenant_id = ? AND artifact_ref = ?
                        """)) {
            statement.setString(1, mediaType);
            statement.setString(2, tenantId.value());
            statement.setString(3, reference.value());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static CertifiedAgentComponentRef component() {
        return new CertifiedAgentComponentRef(
                CertifiedAgentComponentRef.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.COMPONENT_ROLE,
                ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK,
                new CertificationId("certification-reference-catalog"),
                lock("certification-manifest", '1'),
                new CandidateId("candidate-reference-catalog"),
                hash('2'),
                lock("source-manifest", '3'),
                lock("input-lock", '4'),
                new VerificationRunId("verification-reference-catalog"),
                lock("verification-result", '5'),
                lock("compatibility", '6'),
                lock("certification-evidence", '7'),
                ReferenceAssemblyConsumerContract.REQUIRED_CERTIFICATION_PROFILE);
    }

    private static CertificationArtifactLock lock(String name, char hashCharacter) {
        ContentHash hash = hash(hashCharacter);
        return new CertificationArtifactLock(
                new ArtifactReference(
                        "factory-reference-catalog/" + name + "/sha256/" + hash.sha256()),
                hash);
    }

    private static ContentHash hash(char character) {
        return new ContentHash(String.valueOf(character).repeat(64));
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record Fixture(
            JdbcDataSource dataSource,
            JdbcArtifactStore store,
            JacksonReferenceAssemblyArtifactCodec codec,
            ReferenceAssemblyProductLineCatalog catalog) {}
}
