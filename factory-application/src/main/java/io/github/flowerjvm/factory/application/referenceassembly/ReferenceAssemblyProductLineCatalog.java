package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.certification.AgentPackProductContract;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Code-owned, exact versioned catalog for the concrete Reference Assembly ProductLine.
 *
 * <p>This catalog is intentionally small and honest: its product is a released, verified manifest
 * graph that contains one certified Agent Pack component. It is not a runnable TOS host. A future
 * TOS ProductLine can replace these fixtures with its own modules and compatibility contract while
 * reusing the same Factory lifecycle.
 */
public final class ReferenceAssemblyProductLineCatalog {
    public enum Entry { LEGACY_PR4, MAINTENANCE_INVESTIGATION_V1 }

    private static final String FIXTURE_MEDIA_TYPE = "application/json";
    private static final byte[] API_SIGNATURE_INDEX_BYTES = (
                    "{\"apiId\":\"reference-agent-api\","
                            + "\"apiVersion\":\"1.0.0\","
                            + "\"schemaVersion\":\"factory.api-signature-index.v1\","
                            + "\"signatures\":[]}")
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] HOST_FIXTURE_BYTES = (
                    "{\"fixtureId\":\"reference-host\","
                            + "\"fixtureVersion\":\"1.0.0\","
                            + "\"productLineId\":\"reference-assembly\","
                            + "\"schemaVersion\":\"factory.reference-host-fixture.v1\"}")
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] POLICY_SNAPSHOT_BYTES = (
                    "{\"actionRuntimeVersion\":\"0.3.3\","
                            + "\"certificationProfile\":\"internal\","
                            + "\"componentRole\":\"embedded-agent-pack\","
                            + "\"flowerVersion\":\"0.1.3\","
                            + "\"policyId\":\"reference-assembly-admission\","
                            + "\"policyVersion\":\"1.0.0\","
                            + "\"schemaVersion\":\"factory.reference-assembly-policy.v1\"}")
            .getBytes(StandardCharsets.UTF_8);

    private static final CertificationArtifactLock API_SIGNATURE_INDEX = lock(
            "factory-reference-assembly/api-signature-index/1.0.0/sha256/",
            API_SIGNATURE_INDEX_BYTES);
    private static final CertificationArtifactLock HOST_FIXTURE = lock(
            "factory-reference-assembly/host-fixture/1.0.0/sha256/",
            HOST_FIXTURE_BYTES);
    private static final CertificationArtifactLock POLICY_SNAPSHOT = lock(
            "factory-reference-assembly/policy-snapshot/1.0.0/sha256/",
            POLICY_SNAPSHOT_BYTES);

    private final ArtifactStore artifacts;
    private final ReferenceAssemblyArtifactCodec codec;
    private final ReferenceAssemblyConsumerContract consumerContract;
    private final byte[] consumerContractBytes;
    private final CertificationArtifactLock consumerContractLock;
    private final ReferenceAssemblyAdmissionPolicy admissionPolicy;
    private final byte[] maintenanceConsumerContractBytes;
    private final CertificationArtifactLock maintenanceConsumerContractLock;
    private final byte[] maintenancePolicySnapshotBytes;
    private final CertificationArtifactLock maintenancePolicySnapshotLock;
    private final ReferenceAssemblyAdmissionPolicy maintenanceAdmissionPolicy;
    private final ReferenceAssemblyAdmissionPolicies admissionPolicies;

    public ReferenceAssemblyProductLineCatalog(
            ArtifactStore artifacts, ReferenceAssemblyArtifactCodec codec) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.consumerContract = new ReferenceAssemblyConsumerContract(
                ReferenceAssemblyConsumerContract.SCHEMA_VERSION,
                ProductLineId.REFERENCE_ASSEMBLY,
                ReferenceAssemblyConsumerContract.CONTRACT_ID,
                ReferenceAssemblyConsumerContract.CONTRACT_VERSION,
                ReferenceAssemblyRequirement.COMPONENT_ROLE,
                ReferenceAssemblyConsumerContract.REQUIRED_CERTIFICATION_PROFILE,
                AgentPackProductContract.lock(),
                API_SIGNATURE_INDEX,
                HOST_FIXTURE,
                ReferenceAssemblyConsumerContract.REQUIRED_FLOWER_VERSION,
                ReferenceAssemblyConsumerContract.REQUIRED_ACTION_RUNTIME_VERSION,
                ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID);
        this.consumerContractBytes = codec.writeConsumerContract(consumerContract);
        this.consumerContractLock = lock(
                "factory-reference-assembly/consumer-contract/1.0.0/sha256/",
                consumerContractBytes);
        this.admissionPolicy = new ReferenceAssemblyAdmissionPolicy(
                consumerContractLock,
                HOST_FIXTURE,
                POLICY_SNAPSHOT,
                AgentPackProductContract.lock(),
                API_SIGNATURE_INDEX,
                ReferenceAssemblyRequirement.COMPONENT_ROLE,
                ReferenceAssemblyConsumerContract.REQUIRED_CERTIFICATION_PROFILE,
                ReferenceAssemblyConsumerContract.REQUIRED_FLOWER_VERSION,
                ReferenceAssemblyConsumerContract.REQUIRED_ACTION_RUNTIME_VERSION,
                CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID);
        var maintenanceContract = new ReferenceAssemblyConsumerContract(
                ReferenceAssemblyConsumerContract.SCHEMA_VERSION,
                ProductLineId.REFERENCE_ASSEMBLY,
                ReferenceAssemblyConsumerContract.CONTRACT_ID,
                ReferenceAssemblyConsumerContract.MAINTENANCE_CONTRACT_VERSION,
                ReferenceAssemblyRequirement.COMPONENT_ROLE,
                ReferenceAssemblyConsumerContract.REQUIRED_CERTIFICATION_PROFILE,
                MaintenanceInvestigationProductContract.lock(),
                MaintenanceInvestigationProductContract.apiSignatureIndexLock(),
                HOST_FIXTURE,
                ReferenceAssemblyConsumerContract.REQUIRED_FLOWER_VERSION,
                ReferenceAssemblyConsumerContract.REQUIRED_ACTION_RUNTIME_VERSION,
                ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID);
        this.maintenanceConsumerContractBytes = codec.writeConsumerContract(maintenanceContract);
        this.maintenanceConsumerContractLock = lock(
                "factory-reference-assembly/consumer-contract/2.0.0/sha256/", maintenanceConsumerContractBytes);
        this.maintenancePolicySnapshotBytes = (
                "{\"actionRuntimeVersion\":\"0.3.3\","
                        + "\"apiSignatureIndexHash\":\""
                        + MaintenanceInvestigationProductContract.apiSignatureIndexLock().hash().sha256() + "\","
                        + "\"certificationProfile\":\"internal\","
                        + "\"componentRole\":\"embedded-agent-pack\","
                        + "\"flowerVersion\":\"0.1.3\","
                        + "\"gateProfile\":\"" + MaintenanceInvestigationProductContract.GATE_PROFILE + "\","
                        + "\"policyId\":\"reference-assembly-admission\","
                        + "\"policyVersion\":\"2.0.0\","
                        + "\"productContractHash\":\"" + MaintenanceInvestigationProductContract.lock().hash().sha256() + "\","
                        + "\"schemaVersion\":\"factory.reference-assembly-policy.v1\"}")
                .getBytes(StandardCharsets.UTF_8);
        this.maintenancePolicySnapshotLock = lock(
                "factory-reference-assembly/policy-snapshot/2.0.0/sha256/", maintenancePolicySnapshotBytes);
        this.maintenanceAdmissionPolicy = new ReferenceAssemblyAdmissionPolicy(
                maintenanceConsumerContractLock, HOST_FIXTURE, maintenancePolicySnapshotLock,
                MaintenanceInvestigationProductContract.lock(), MaintenanceInvestigationProductContract.apiSignatureIndexLock(),
                ReferenceAssemblyRequirement.COMPONENT_ROLE,
                ReferenceAssemblyConsumerContract.REQUIRED_CERTIFICATION_PROFILE,
                ReferenceAssemblyConsumerContract.REQUIRED_FLOWER_VERSION,
                ReferenceAssemblyConsumerContract.REQUIRED_ACTION_RUNTIME_VERSION,
                CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID,
                ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID);
        this.admissionPolicies = new ReferenceAssemblyAdmissionPolicies(List.of(admissionPolicy, maintenanceAdmissionPolicy));
    }

    public ReferenceAssemblyAdmissionPolicy admissionPolicy() {
        return admissionPolicy;
    }

    public ReferenceAssemblyAdmissionPolicy admissionPolicy(Entry entry) {
        return switch (Objects.requireNonNull(entry, "entry")) {
            case LEGACY_PR4 -> admissionPolicy;
            case MAINTENANCE_INVESTIGATION_V1 -> maintenanceAdmissionPolicy;
        };
    }

    /** The host must inject this exact registry into request, assembly and independent inspection. */
    public ReferenceAssemblyAdmissionPolicies admissionPolicies() { return admissionPolicies; }

    /** Exact API fixture an Agent Pack must bind before it can enter this ProductLine. */
    public CertificationArtifactLock expectedApiSignatureIndex() {
        return API_SIGNATURE_INDEX;
    }

    public CertificationArtifactLock expectedApiSignatureIndex(Entry entry) {
        return admissionPolicy(entry).expectedApiSignatureIndex();
    }

    public CertificationArtifactLock consumerContractLock() {
        return consumerContractLock;
    }

    public CertificationArtifactLock consumerContractLock(Entry entry) {
        return admissionPolicy(entry).approvedConsumerContract();
    }

    public CertificationArtifactLock hostFixtureLock() {
        return HOST_FIXTURE;
    }

    public CertificationArtifactLock hostFixtureLock(Entry entry) {
        return admissionPolicy(entry).approvedHostFixture();
    }

    public CertificationArtifactLock policySnapshotLock() {
        return POLICY_SNAPSHOT;
    }

    public CertificationArtifactLock policySnapshotLock(Entry entry) {
        return admissionPolicy(entry).approvedPolicySnapshot();
    }

    /** Idempotently installs every code-owned input artifact in one trusted tenant scope. */
    public void provisionTenant(TenantId trustedTenantId) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        stageExact(AgentPackProductContract.artifact(trustedTenantId));
        stage(trustedTenantId, API_SIGNATURE_INDEX, FIXTURE_MEDIA_TYPE, API_SIGNATURE_INDEX_BYTES);
        stage(trustedTenantId, HOST_FIXTURE, FIXTURE_MEDIA_TYPE, HOST_FIXTURE_BYTES);
        stage(trustedTenantId, POLICY_SNAPSHOT, FIXTURE_MEDIA_TYPE, POLICY_SNAPSHOT_BYTES);
        stage(trustedTenantId, consumerContractLock, ReferenceAssemblyArtifactCodec.MEDIA_TYPE,
                consumerContractBytes);
    }

    /** Installs only the explicitly selected contract; the old overload remains legacy-only. */
    public void provisionTenant(TenantId trustedTenantId, Entry entry) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        if (Objects.requireNonNull(entry, "entry") == Entry.LEGACY_PR4) {
            provisionTenant(trustedTenantId);
            return;
        }
        MaintenanceInvestigationProductContract.artifacts(trustedTenantId).forEach(this::stageExact);
        stage(trustedTenantId, HOST_FIXTURE, FIXTURE_MEDIA_TYPE, HOST_FIXTURE_BYTES);
        stage(trustedTenantId, maintenancePolicySnapshotLock, FIXTURE_MEDIA_TYPE, maintenancePolicySnapshotBytes);
        stage(trustedTenantId, maintenanceConsumerContractLock, ReferenceAssemblyArtifactCodec.MEDIA_TYPE,
                maintenanceConsumerContractBytes);
    }

    /** Stages the canonical requirement that puts one certified Agent Pack on this line. */
    public CertificationArtifactLock stageRequirement(
            TenantId trustedTenantId, CertifiedAgentComponentRef component) {
        return stageRequirement(trustedTenantId, component, Entry.LEGACY_PR4);
    }

    /** Selects a contract explicitly; component compatibility is revalidated by the independent read gate. */
    public CertificationArtifactLock stageRequirement(
            TenantId trustedTenantId, CertifiedAgentComponentRef component, Entry entry) {
        Objects.requireNonNull(trustedTenantId, "trustedTenantId");
        Objects.requireNonNull(component, "component");
        ReferenceAssemblyAdmissionPolicy selected = admissionPolicy(entry);
        provisionTenant(trustedTenantId, entry);
        ReferenceAssemblyRequirement requirement = new ReferenceAssemblyRequirement(
                ReferenceAssemblyRequirement.SCHEMA_VERSION,
                ProductLineId.REFERENCE_ASSEMBLY,
                selected.approvedConsumerContract(),
                selected.approvedHostFixture(),
                selected.approvedPolicySnapshot(),
                component);
        byte[] canonical = codec.writeRequirement(requirement);
        CertificationArtifactLock requirementLock = lock(
                "factory-reference-assembly/requirement/sha256/", canonical);
        stage(trustedTenantId, requirementLock, ReferenceAssemblyArtifactCodec.MEDIA_TYPE, canonical);
        return requirementLock;
    }

    private void stage(
            TenantId tenantId,
            CertificationArtifactLock lock,
            String mediaType,
            byte[] content) {
        stageExact(new Artifact(
                tenantId, lock.reference(), lock.hash(), mediaType, content));
    }

    private void stageExact(Artifact expected) {
        try {
            ArtifactReference stored = artifacts.store(expected);
            if (!expected.reference().equals(stored)) {
                throw conflict("artifact store returned a different Reference Assembly fixture reference", null);
            }
        } catch (ReferenceAssemblyException stable) {
            throw stable;
        } catch (RuntimeException possibleDuplicate) {
            // Exact immutable re-observation below decides whether this was a harmless race.
        }
        Artifact observed;
        try {
            observed = artifacts.find(expected.tenantId(), expected.reference()).orElse(null);
        } catch (RuntimeException corrupt) {
            throw conflict("Reference Assembly fixture failed its immutable read gate", corrupt);
        }
        if (observed == null
                || !observed.tenantId().equals(expected.tenantId())
                || !observed.reference().equals(expected.reference())
                || !observed.contentHash().equals(expected.contentHash())
                || !observed.mediaType().equals(expected.mediaType())
                || !Arrays.equals(observed.content(), expected.content())) {
            throw conflict("Reference Assembly fixture collided with different immutable content", null);
        }
    }

    private static CertificationArtifactLock lock(String prefix, byte[] content) {
        ContentHash hash = sha256(content);
        return new CertificationArtifactLock(
                new ArtifactReference(prefix + hash.sha256()), hash);
    }

    private static ContentHash sha256(byte[] content) {
        try {
            return new ContentHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static ReferenceAssemblyException conflict(String message, RuntimeException cause) {
        return cause == null
                ? new ReferenceAssemblyException(ReferenceAssemblyException.STAGING_CONFLICT, message)
                : new ReferenceAssemblyException(
                        ReferenceAssemblyException.STAGING_CONFLICT, message, cause);
    }
}
