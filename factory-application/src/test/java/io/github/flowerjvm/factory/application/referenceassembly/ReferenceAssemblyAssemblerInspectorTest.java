package io.github.flowerjvm.factory.application.referenceassembly;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.flowerjvm.factory.application.candidate.CandidateVersion;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionStatus;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationArtifactCodec;
import io.github.flowerjvm.factory.application.certification.ResolvedCertifiedAgentComponent;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.contracts.verification.CandidateSourceManifest;
import io.github.flowerjvm.factory.application.verification.VerificationRun;
import io.github.flowerjvm.factory.application.verification.VerificationRunStatus;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.AgentPackCompatibilityDescriptor;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationEvidenceManifest;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentManifest;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.ids.WorkOrderId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyInspectionReport;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseSubject;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import io.github.flowerjvm.factory.contracts.verification.VerificationDisposition;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReferenceAssemblyAssemblerInspectorTest {
    @Test
    void maintenanceEntryAssemblesAndIndependentlyInspectsExactNewProductAndApiLocks() {
        Fixture fixture = maintenanceFixture();
        var catalog = new ReferenceAssemblyProductLineCatalog(fixture.store, fixture.codec);
        var entry = ReferenceAssemblyProductLineCatalog.Entry.MAINTENANCE_INVESTIGATION_V1;
        var requirement = catalog.stageRequirement(fixture.tenant, fixture.resolved.reference(), entry);
        var assembler = new ReferenceAssemblyAssembler(fixture.store, fixture.codec, fixture.gate, catalog.admissionPolicies());
        var inspector = new ReferenceAssemblyInspector(fixture.store, fixture.codec, fixture.gate, catalog.admissionPolicies());

        var assembled = assembler.assemble(fixture.tenant, requirement);
        var inspected = inspector.inspect(fixture.tenant, assembled.manifestLock());

        assertTrue(inspected.passed());
        assertEquals(catalog.consumerContractLock(entry), assembled.manifest().consumerContract());
        assertEquals(catalog.policySnapshotLock(entry), inspected.report().policySnapshot());
        assertEquals(2, fixture.gate.calls.get());
    }

    @Test
    void maintenanceComponentCannotEnterLegacyEntryAndLegacyComponentCannotEnterMaintenanceEntry() {
        for (boolean newComponent : List.of(true, false)) {
            Fixture fixture = newComponent ? maintenanceFixture() : new Fixture(
                    io.github.flowerjvm.factory.application.certification.AgentPackProductContract.lock(),
                    new ReferenceAssemblyProductLineCatalog(new MemoryArtifactStore(), new TestCodec()).expectedApiSignatureIndex(),
                    CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID);
            var catalog = new ReferenceAssemblyProductLineCatalog(fixture.store, fixture.codec);
            var wrongEntry = newComponent ? ReferenceAssemblyProductLineCatalog.Entry.LEGACY_PR4
                    : ReferenceAssemblyProductLineCatalog.Entry.MAINTENANCE_INVESTIGATION_V1;
            var requirement = catalog.stageRequirement(fixture.tenant, fixture.resolved.reference(), wrongEntry);
            var assembler = new ReferenceAssemblyAssembler(fixture.store, fixture.codec, fixture.gate, catalog.admissionPolicies());
            assertCode(ReferenceAssemblyException.CONTRACT_MISMATCH,
                    () -> assembler.assemble(fixture.tenant, requirement));
        }
    }

    @Test
    void registeredConsumerCannotBorrowAnotherEntriesPolicySnapshotInAssemblyOrInspection() {
        Fixture fixture = maintenanceFixture();
        var catalog = new ReferenceAssemblyProductLineCatalog(fixture.store, fixture.codec);
        var entry = ReferenceAssemblyProductLineCatalog.Entry.MAINTENANCE_INVESTIGATION_V1;
        catalog.provisionTenant(fixture.tenant);
        catalog.provisionTenant(fixture.tenant, entry);
        var requirement = fixture.stageRequirement(catalog.consumerContractLock(entry),
                catalog.hostFixtureLock(entry), catalog.policySnapshotLock());
        var manifest = fixture.stageManifest(requirement, catalog.consumerContractLock(entry),
                catalog.hostFixtureLock(entry), catalog.policySnapshotLock());
        var assembler = new ReferenceAssemblyAssembler(fixture.store, fixture.codec, fixture.gate, catalog.admissionPolicies());
        var inspector = new ReferenceAssemblyInspector(fixture.store, fixture.codec, fixture.gate, catalog.admissionPolicies());

        assertCode(ReferenceAssemblyException.ADMISSION_POLICY_MISMATCH,
                () -> assembler.assemble(fixture.tenant, requirement));
        assertRejectedCheck(inspector.inspect(fixture.tenant, manifest), "manifest-locks");
    }

    private static Fixture maintenanceFixture() {
        return new Fixture(MaintenanceInvestigationProductContract.lock(),
                MaintenanceInvestigationProductContract.apiSignatureIndexLock(), CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID);
    }

    @Test
    void componentResolutionIsBoundedAndDoesNotStageAnAssemblyManifest() {
        Fixture fixture = new Fixture();
        int writesBeforeResolution = fixture.store.writes.get();

        ReferenceAssemblyAssembler.ComponentResolution resolution =
                fixture.assembler().resolveComponent(fixture.tenant, fixture.requirementLock);

        assertEquals(fixture.requirement, resolution.requirement());
        assertEquals(fixture.contract, resolution.consumerContract());
        assertEquals(fixture.resolved, resolution.component());
        assertEquals(writesBeforeResolution, fixture.store.writes.get());
        assertEquals(1, fixture.gate.calls.get());
    }

    @Test
    void sameExactInputsStageDeterministicManifestBytesAndLock() {
        Fixture fixture = new Fixture();
        ReferenceAssemblyAssembler assembler = fixture.assembler();

        ReferenceAssemblyAssembler.AssemblyResult first =
                assembler.assemble(fixture.tenant, fixture.requirementLock);
        byte[] firstBytes = fixture.store.require(first.manifestLock()).content();
        int writesAfterFirstAssembly = fixture.store.writes.get();
        ReferenceAssemblyAssembler.AssemblyResult second =
                assembler.assemble(fixture.tenant, fixture.requirementLock);

        assertEquals(first, second);
        assertArrayEquals(firstBytes, fixture.store.require(second.manifestLock()).content());
        assertEquals(writesAfterFirstAssembly, fixture.store.writes.get());
        assertTrue(first.manifestLock().reference().value()
                .startsWith(ReferenceAssemblyArtifactSupport.MANIFEST_REFERENCE_PREFIX));
    }

    @Test
    void rejectsArtifactWhoseDeclaredHashDoesNotMatchActualBytes() {
        Fixture fixture = new Fixture();
        fixture.store.replaceContentKeepingDeclaredHash(
                fixture.tenant,
                fixture.requirementLock,
                "tampered".getBytes(StandardCharsets.UTF_8));

        assertCode(
                ReferenceAssemblyException.ARTIFACT_INVALID,
                () -> fixture.assembler().assemble(fixture.tenant, fixture.requirementLock));
    }

    @Test
    void rejectsDifferentResolvedComponentExactLock() {
        Fixture fixture = new Fixture();
        CertifiedAgentComponentRef wrong = fixture.componentWithManifest(
                fixture.lock("other-certification-manifest"));
        fixture.gate.resolved = new ResolvedCertifiedAgentComponent(
                wrong,
                fixture.resolved.certification(),
                fixture.resolved.inputLock(),
                fixture.resolved.evidence(),
                fixture.resolved.manifest(),
                fixture.resolved.compatibilityDescriptor(),
                fixture.resolved.candidate(),
                fixture.resolved.verificationRun());

        assertCode(
                ReferenceAssemblyException.COMPONENT_MISMATCH,
                () -> fixture.assembler().assemble(fixture.tenant, fixture.requirementLock));
    }

    @Test
    void rejectsHostFixtureAndConsumerContractMismatch() {
        Fixture wrongFixture = new Fixture();
        ReferenceAssemblyConsumerContract fixtureContract = wrongFixture.consumerContract(
                wrongFixture.productContract,
                wrongFixture.apiSignature,
                wrongFixture.stageOpaque("different-host", "different-host"));
        ReplacementGraph fixtureGraph = wrongFixture.stageRequirement(fixtureContract);
        assertCode(
                ReferenceAssemblyException.FIXTURE_MISMATCH,
                () -> wrongFixture.assembler(wrongFixture.policy(fixtureGraph.consumerContract()))
                        .assemble(wrongFixture.tenant, fixtureGraph.requirement()));

        Fixture wrongContract = new Fixture();
        ReferenceAssemblyConsumerContract productContract = wrongContract.consumerContract(
                wrongContract.lock("different-product-contract"),
                wrongContract.apiSignature,
                wrongContract.hostFixture);
        ReplacementGraph contractGraph = wrongContract.stageRequirement(productContract);
        assertCode(
                ReferenceAssemblyException.CONTRACT_MISMATCH,
                () -> wrongContract.assembler(wrongContract.policy(contractGraph.consumerContract()))
                        .assemble(wrongContract.tenant, contractGraph.requirement()));
    }

    @Test
    void rejectsSelfDeclaredConsumerContractOutsideTrustedAdmissionPolicy() {
        Fixture fixture = new Fixture();
        ReplacementGraph selfDeclared = fixture.stageRequirement(fixture.contract);

        assertCode(
                ReferenceAssemblyException.ADMISSION_POLICY_MISMATCH,
                () -> fixture.assembler().assemble(fixture.tenant, selfDeclared.requirement()));
    }

    @Test
    void inspectorIndependentlyRereadsWholeGraphAndCertifiedComponent() {
        Fixture fixture = new Fixture();
        ReferenceAssemblyAssembler.AssemblyResult assembled =
                fixture.assembler().assemble(fixture.tenant, fixture.requirementLock);
        fixture.store.clearReads();
        fixture.gate.calls.set(0);

        ReferenceAssemblyInspector.InspectionResult inspected =
                fixture.inspector().inspect(fixture.tenant, assembled.manifestLock());

        assertTrue(inspected.passed());
        assertEquals(ReferenceAssemblyInspectionReport.REQUIRED_CHECK_IDS,
                inspected.report().checks().stream().map(check -> check.checkId()).toList());
        assertEquals(1, fixture.gate.calls.get());
        assertTrue(fixture.store.reads(assembled.manifestLock()) >= 1);
        assertTrue(fixture.store.reads(fixture.requirementLock) >= 1);
        assertTrue(fixture.store.reads(fixture.consumerContractLock) >= 1);
        assertTrue(fixture.store.reads(fixture.hostFixture) >= 1);
        assertTrue(fixture.store.reads(fixture.policySnapshot) >= 1);
        assertEquals(inspected.report(), fixture.codec.readInspectionReport(
                fixture.store.require(inspected.reportLock()).content()));
    }

    @Test
    void inspectorStagesFixedOrderRejectedReportForStoredComponentLockMismatch() {
        Fixture fixture = new Fixture();
        ReferenceAssemblyAssembler.AssemblyResult assembled =
                fixture.assembler().assemble(fixture.tenant, fixture.requirementLock);
        CertifiedAgentComponentRef differentComponent = fixture.componentWithManifest(
                fixture.lock("inspection-different-certification-manifest"));
        ReferenceAssemblyManifest mismatched = new ReferenceAssemblyManifest(
                ReferenceAssemblyManifest.SCHEMA_VERSION,
                ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                assembled.manifest().assemblyAlgorithmId(),
                assembled.manifest().requirement(),
                assembled.manifest().consumerContract(),
                assembled.manifest().hostFixture(),
                assembled.manifest().policySnapshot(),
                differentComponent);
        CertificationArtifactLock mismatchedLock = new ReferenceAssemblyArtifactSupport(
                fixture.store, fixture.codec).stageManifest(fixture.tenant, mismatched);

        ReferenceAssemblyInspector.InspectionResult inspected =
                fixture.inspector().inspect(fixture.tenant, mismatchedLock);

        assertFalse(inspected.passed());
        assertEquals(ReferenceAssemblyInspectionReport.REJECTED, inspected.report().status());
        assertEquals(ReferenceAssemblyInspectionReport.REQUIRED_CHECK_IDS,
                inspected.report().checks().stream().map(check -> check.checkId()).toList());
        assertFalse(inspected.report().checks().stream()
                .filter(check -> "component-certification".equals(check.checkId()))
                .findFirst().orElseThrow().passed());
        assertFalse(inspected.report().checks().stream()
                .filter(check -> "manifest-locks".equals(check.checkId()))
                .findFirst().orElseThrow().passed());
        assertTrue(fixture.store.find(fixture.tenant, inspected.reportLock().reference()).isPresent());
    }

    @Test
    void inspectorStagesRejectedEvidenceForSelfDeclaredConsumerContract() {
        Fixture fixture = new Fixture();
        ReplacementGraph selfDeclared = fixture.stageRequirement(fixture.contract);
        CertificationArtifactLock manifest = fixture.stageManifest(
                selfDeclared.requirement(),
                selfDeclared.consumerContract(),
                fixture.hostFixture,
                fixture.policySnapshot);

        ReferenceAssemblyInspector.InspectionResult inspected =
                fixture.inspector().inspect(fixture.tenant, manifest);

        assertRejectedCheck(inspected, "consumer-contract");
        assertTrue(fixture.store.find(fixture.tenant, inspected.reportLock().reference()).isPresent());
    }

    @Test
    void inspectorStagesRejectedEvidenceForUnapprovedHostFixture() {
        Fixture fixture = new Fixture();
        CertificationArtifactLock unapprovedHost =
                fixture.stageOpaque("unapproved-host", "unapproved-host");
        CertificationArtifactLock requirement = fixture.stageRequirement(
                fixture.consumerContractLock, unapprovedHost, fixture.policySnapshot);
        CertificationArtifactLock manifest = fixture.stageManifest(
                requirement,
                fixture.consumerContractLock,
                unapprovedHost,
                fixture.policySnapshot);

        ReferenceAssemblyInspector.InspectionResult inspected =
                fixture.inspector().inspect(fixture.tenant, manifest);

        assertRejectedCheck(inspected, "host-fixture");
        assertTrue(fixture.store.find(fixture.tenant, inspected.reportLock().reference()).isPresent());
    }

    @Test
    void inspectorStagesRejectedEvidenceForUnapprovedPolicySnapshot() {
        Fixture fixture = new Fixture();
        CertificationArtifactLock unapprovedPolicy =
                fixture.stageOpaque("unapproved-policy", "unapproved-policy");
        CertificationArtifactLock requirement = fixture.stageRequirement(
                fixture.consumerContractLock, fixture.hostFixture, unapprovedPolicy);
        CertificationArtifactLock manifest = fixture.stageManifest(
                requirement,
                fixture.consumerContractLock,
                fixture.hostFixture,
                unapprovedPolicy);

        ReferenceAssemblyInspector.InspectionResult inspected =
                fixture.inspector().inspect(fixture.tenant, manifest);

        assertRejectedCheck(inspected, "manifest-locks");
        assertTrue(fixture.store.find(fixture.tenant, inspected.reportLock().reference()).isPresent());
    }

    private static void assertCode(String expectedCode, Runnable work) {
        ReferenceAssemblyException failure =
                assertThrows(ReferenceAssemblyException.class, work::run);
        assertEquals(expectedCode, failure.code());
    }

    private static void assertRejectedCheck(
            ReferenceAssemblyInspector.InspectionResult inspected, String failedCheckId) {
        assertFalse(inspected.passed());
        assertEquals(ReferenceAssemblyInspectionReport.REJECTED, inspected.report().status());
        assertEquals(
                ReferenceAssemblyInspectionReport.REQUIRED_CHECK_IDS,
                inspected.report().checks().stream().map(check -> check.checkId()).toList());
        assertFalse(inspected.report().checks().stream()
                .filter(check -> failedCheckId.equals(check.checkId()))
                .findFirst()
                .orElseThrow()
                .passed());
    }

    private static final class Fixture {
        private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
        private final TenantId tenant = new TenantId("tenant-reference-assembly");
        private final MemoryArtifactStore store = new MemoryArtifactStore();
        private final TestCodec codec = new TestCodec();
        private final CertificationArtifactLock hostFixture = stageOpaque("host-fixture", "host-fixture");
        private final CertificationArtifactLock policySnapshot = stageOpaque("policy", "policy");
        private final CertificationArtifactLock productContract;
        private final CertificationArtifactLock apiSignature;
        private final String sourceAlgorithm;
        private final ResolvedCertifiedAgentComponent resolved;
        private final CountingGate gate;
        private final ReferenceAssemblyConsumerContract contract;
        private final CertificationArtifactLock consumerContractLock;
        private final ReferenceAssemblyRequirement requirement;
        private final CertificationArtifactLock requirementLock;
        private final ReferenceAssemblyAdmissionPolicy admissionPolicy;

        private Fixture() { this(null, null, "sha256-ordinal-v1"); }

        private Fixture(CertificationArtifactLock product, CertificationArtifactLock api, String sourceAlgorithm) {
            this.productContract = product == null ? lock("agent-product-contract") : product;
            this.apiSignature = api == null ? lock("api-signature") : api;
            this.sourceAlgorithm = sourceAlgorithm;
            this.resolved = resolvedComponent();
            this.gate = new CountingGate(resolved);
            this.contract = consumerContract(productContract, apiSignature, hostFixture);
            this.consumerContractLock = stageCanonical("consumer-contract", codec.writeConsumerContract(contract));
            this.requirement = new ReferenceAssemblyRequirement(ReferenceAssemblyRequirement.SCHEMA_VERSION,
                    ReferenceAssemblyRequirement.PRODUCT_LINE_ID, consumerContractLock, hostFixture, policySnapshot,
                    resolved.reference());
            this.requirementLock = stageCanonical("requirement", codec.writeRequirement(requirement));
            this.admissionPolicy = policy(consumerContractLock);
        }

        private ReferenceAssemblyAssembler assembler() {
            return assembler(admissionPolicy);
        }

        private ReferenceAssemblyAssembler assembler(
                ReferenceAssemblyAdmissionPolicy policy) {
            return new ReferenceAssemblyAssembler(store, codec, gate, policy);
        }

        private ReferenceAssemblyInspector inspector() {
            return new ReferenceAssemblyInspector(store, codec, gate, admissionPolicy);
        }

        private ReplacementGraph stageRequirement(
                ReferenceAssemblyConsumerContract replacement) {
            CertificationArtifactLock replacementContract = stageCanonical(
                    "consumer-contract-replacement", codec.writeConsumerContract(replacement));
            CertificationArtifactLock replacementRequirement = stageRequirement(
                    replacementContract, hostFixture, policySnapshot);
            return new ReplacementGraph(replacementRequirement, replacementContract);
        }

        private CertificationArtifactLock stageRequirement(
                CertificationArtifactLock consumer,
                CertificationArtifactLock host,
                CertificationArtifactLock policy) {
            return stageCanonical(
                    "requirement-replacement",
                    codec.writeRequirement(new ReferenceAssemblyRequirement(
                            ReferenceAssemblyRequirement.SCHEMA_VERSION,
                            ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                            consumer,
                            host,
                            policy,
                            resolved.reference())));
        }

        private CertificationArtifactLock stageManifest(
                CertificationArtifactLock lockedRequirement,
                CertificationArtifactLock consumer,
                CertificationArtifactLock host,
                CertificationArtifactLock policy) {
            ReferenceAssemblyManifest manifest = new ReferenceAssemblyManifest(
                    ReferenceAssemblyManifest.SCHEMA_VERSION,
                    ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                    ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID,
                    lockedRequirement,
                    consumer,
                    host,
                    policy,
                    resolved.reference());
            return new ReferenceAssemblyArtifactSupport(store, codec)
                    .stageManifest(tenant, manifest);
        }

        private ReferenceAssemblyAdmissionPolicy policy(
                CertificationArtifactLock approvedConsumerContract) {
            return new ReferenceAssemblyAdmissionPolicy(
                    approvedConsumerContract,
                    hostFixture,
                    policySnapshot,
                    productContract,
                    apiSignature,
                    ReferenceAssemblyRequirement.COMPONENT_ROLE,
                    ReferenceAssemblyConsumerContract.REQUIRED_CERTIFICATION_PROFILE,
                    ReferenceAssemblyConsumerContract.REQUIRED_FLOWER_VERSION,
                    ReferenceAssemblyConsumerContract.REQUIRED_ACTION_RUNTIME_VERSION,
                    sourceAlgorithm,
                    ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID);
        }

        private ReferenceAssemblyConsumerContract consumerContract(
                CertificationArtifactLock requiredProduct,
                CertificationArtifactLock requiredApi,
                CertificationArtifactLock requiredHost) {
            return new ReferenceAssemblyConsumerContract(
                    ReferenceAssemblyConsumerContract.SCHEMA_VERSION,
                    ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                    ReferenceAssemblyConsumerContract.CONTRACT_ID,
                    ReferenceAssemblyConsumerContract.CONTRACT_VERSION,
                    ReferenceAssemblyRequirement.COMPONENT_ROLE,
                    ReferenceAssemblyConsumerContract.REQUIRED_CERTIFICATION_PROFILE,
                    requiredProduct,
                    requiredApi,
                    requiredHost,
                    ReferenceAssemblyConsumerContract.REQUIRED_FLOWER_VERSION,
                    ReferenceAssemblyConsumerContract.REQUIRED_ACTION_RUNTIME_VERSION,
                    ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID);
        }

        private ResolvedCertifiedAgentComponent resolvedComponent() {
            BuildSessionId buildSessionId = new BuildSessionId("assembly-build");
            WorkOrderId workOrderId = new WorkOrderId("assembly-work-order");
            CandidateId candidateId = new CandidateId("assembly-candidate");
            VerificationRunId verificationId = new VerificationRunId("assembly-verification");
            CertificationId certificationId = new CertificationId("assembly-certification");
            CertificationArtifactLock source = lock("source");
            CertificationArtifactLock dependency = lock("dependency");
            CertificationArtifactLock toolchain = lock("toolchain");
            CertificationArtifactLock generation = lock("generation-input");
            CertificationArtifactLock verificationResult = lock("verification-result");
            CertificationArtifactLock compatibilityLock = lock("compatibility");
            CertificationArtifactLock inputArtifact = lock("certification-input");
            CertificationArtifactLock evidenceLock = lock("certification-evidence");
            CertificationArtifactLock certificationManifestLock = lock("certification-manifest");
            ContentHash fixtureHash = hash("fixture-set");
            CertificationInputLock input = new CertificationInputLock(
                    CertificationInputLock.SCHEMA_VERSION,
                    tenant,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    buildSessionId,
                    workOrderId,
                    candidateId,
                    source.hash(),
                    source,
                    dependency,
                    toolchain,
                    generation,
                    productContract,
                    apiSignature,
                    sourceAlgorithm,
                    "internal",
                    verificationId,
                    "verification-action",
                    verificationResult,
                    fixtureHash,
                    policySnapshot,
                    compatibilityLock,
                    "internal",
                    "0.2.0",
                    "0.1.3",
                    "0.3.3");
            AgentPackCompatibilityDescriptor compatibility = new AgentPackCompatibilityDescriptor(
                    AgentPackCompatibilityDescriptor.SCHEMA_VERSION,
                    tenant,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    candidateId,
                    source.hash(),
                    productContract,
                    apiSignature,
                    dependency,
                    toolchain,
                    "internal",
                    sourceAlgorithm,
                    "0.2.0",
                    "0.1.3",
                    "0.3.3");
            CertificationEvidenceManifest evidence = new CertificationEvidenceManifest(
                    CertificationEvidenceManifest.SCHEMA_VERSION,
                    tenant,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    certificationId,
                    candidateId,
                    source.hash(),
                    inputArtifact.hash(),
                    verificationId,
                    "verification-action",
                    verificationResult.hash(),
                    compatibilityLock.hash(),
                    "internal",
                    "0.2.0",
                    "0.1.3",
                    "0.3.3");
            CertifiedAgentComponentManifest componentManifest = new CertifiedAgentComponentManifest(
                    CertifiedAgentComponentManifest.SCHEMA_VERSION,
                    tenant,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    certificationId,
                    candidateId,
                    source.hash(),
                    source,
                    inputArtifact,
                    verificationId,
                    verificationResult,
                    compatibilityLock,
                    evidenceLock,
                    "internal",
                    "0.2.0",
                    NOW,
                    NOW.plusSeconds(3600),
                    CertifiedAgentComponentManifest.CERTIFIED_STATUS);
            Certification certification = Certification.requested(
                            certificationId, input, inputArtifact, NOW)
                    .certify(
                            certificationManifestLock,
                            evidenceLock,
                            "certification-action",
                            NOW,
                            Optional.of(NOW.plusSeconds(3600)));
            CertifiedAgentComponentRef reference = new CertifiedAgentComponentRef(
                    CertifiedAgentComponentRef.SCHEMA_VERSION,
                    ReferenceAssemblyRequirement.COMPONENT_ROLE,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    certificationId,
                    certificationManifestLock,
                    candidateId,
                    source.hash(),
                    source,
                    inputArtifact,
                    verificationId,
                    verificationResult,
                    compatibilityLock,
                    evidenceLock,
                    "internal");
            CandidateVersion candidate = new CandidateVersion(
                    candidateId,
                    tenant,
                    buildSessionId,
                    Optional.empty(),
                    source.reference(),
                    source.hash(),
                    dependency.reference(),
                    dependency.hash(),
                    toolchain.reference(),
                    toolchain.hash(),
                    CandidateVersionStatus.GENERATED,
                    workOrderId,
                    NOW);
            VerificationRun verification = new VerificationRun(
                    verificationId,
                    tenant,
                    buildSessionId,
                    candidateId,
                    source.hash(),
                    "internal",
                    toolchain.hash(),
                    fixtureHash,
                    VerificationRunStatus.PASSED,
                    Optional.of(verificationResult.reference()),
                    Optional.of(verificationResult.hash()),
                    Optional.of("VERIFICATION_PASSED"),
                    Optional.of(VerificationDisposition.REVIEW_ELIGIBLE),
                    Optional.of(NOW),
                    Optional.of(NOW),
                    2,
                    NOW,
                    NOW);
            return new ResolvedCertifiedAgentComponent(
                    reference,
                    certification,
                    input,
                    evidence,
                    componentManifest,
                    compatibility,
                    candidate,
                    verification);
        }

        private CertifiedAgentComponentRef componentWithManifest(
                CertificationArtifactLock manifestLock) {
            CertifiedAgentComponentRef current = resolved.reference();
            return new CertifiedAgentComponentRef(
                    current.schemaVersion(),
                    current.componentRole(),
                    current.productLineId(),
                    current.artifactType(),
                    current.certificationId(),
                    manifestLock,
                    current.candidateId(),
                    current.candidateHash(),
                    current.sourceManifest(),
                    current.inputLockManifest(),
                    current.verificationRunId(),
                    current.verificationResultManifest(),
                    current.compatibilityDescriptor(),
                    current.certificationEvidence(),
                    current.certificationProfile());
        }

        private CertificationArtifactLock stageOpaque(String name, String value) {
            return stage(name, "application/octet-stream", value.getBytes(StandardCharsets.UTF_8));
        }

        private CertificationArtifactLock stageCanonical(String name, byte[] content) {
            return stage(name, ReferenceAssemblyArtifactCodec.MEDIA_TYPE, content);
        }

        private CertificationArtifactLock stage(String name, String mediaType, byte[] content) {
            ContentHash hash = ReferenceAssemblyArtifactSupport.sha256(content);
            ArtifactReference reference = new ArtifactReference(
                    "test-reference-assembly/" + name + "/sha256/" + hash.sha256());
            store.store(new Artifact(tenant, reference, hash, mediaType, content));
            return new CertificationArtifactLock(reference, hash);
        }

        private CertificationArtifactLock lock(String name) {
            ContentHash hash = hash(name);
            return new CertificationArtifactLock(
                    new ArtifactReference("locked/" + name + "/sha256/" + hash.sha256()),
                    hash);
        }
    }

    private record ReplacementGraph(
            CertificationArtifactLock requirement,
            CertificationArtifactLock consumerContract) {}

    private static final class CountingGate
            implements io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate {
        private final AtomicInteger calls = new AtomicInteger();
        private ResolvedCertifiedAgentComponent resolved;

        private CountingGate(ResolvedCertifiedAgentComponent resolved) {
            this.resolved = resolved;
        }

        @Override
        public ResolvedCertifiedAgentComponent resolve(
                TenantId trustedTenantId, CertifiedAgentComponentRef reference) {
            calls.incrementAndGet();
            return resolved;
        }
    }

    private static final class MemoryArtifactStore implements ArtifactStore {
        private final Map<String, Artifact> values = new LinkedHashMap<>();
        private final Map<String, AtomicInteger> reads = new LinkedHashMap<>();
        private final AtomicInteger writes = new AtomicInteger();

        @Override
        public ArtifactReference store(Artifact artifact) {
            String key = key(artifact.tenantId(), artifact.reference());
            Artifact existing = values.putIfAbsent(key, artifact);
            if (existing != null
                    && (!existing.contentHash().equals(artifact.contentHash())
                            || !existing.mediaType().equals(artifact.mediaType())
                            || !Arrays.equals(existing.content(), artifact.content()))) {
                throw new IllegalStateException("artifact conflict");
            }
            if (existing == null) {
                writes.incrementAndGet();
            }
            return artifact.reference();
        }

        @Override
        public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            reads.computeIfAbsent(key(tenantId, reference), ignored -> new AtomicInteger())
                    .incrementAndGet();
            return Optional.ofNullable(values.get(key(tenantId, reference)));
        }

        private Artifact require(CertificationArtifactLock lock) {
            return values.get(key(new TenantId("tenant-reference-assembly"), lock.reference()));
        }

        private int reads(CertificationArtifactLock lock) {
            return reads.getOrDefault(
                    key(new TenantId("tenant-reference-assembly"), lock.reference()),
                    new AtomicInteger()).get();
        }

        private void clearReads() {
            reads.clear();
        }

        private void replaceContentKeepingDeclaredHash(
                TenantId tenantId, CertificationArtifactLock lock, byte[] content) {
            Artifact current = values.get(key(tenantId, lock.reference()));
            values.put(
                    key(tenantId, lock.reference()),
                    new Artifact(
                            tenantId,
                            current.reference(),
                            current.contentHash(),
                            current.mediaType(),
                            content));
        }

        private static String key(TenantId tenantId, ArtifactReference reference) {
            return tenantId.value() + "\u0000" + reference.value();
        }
    }

    private static final class TestCodec implements ReferenceAssemblyArtifactCodec {
        private final Map<String, Object> decoded = new LinkedHashMap<>();

        @Override
        public byte[] writeRequirement(ReferenceAssemblyRequirement value) {
            return write("requirement", value);
        }

        @Override
        public ReferenceAssemblyRequirement readRequirement(byte[] content) {
            return read("requirement", content, ReferenceAssemblyRequirement.class);
        }

        @Override
        public byte[] writeConsumerContract(ReferenceAssemblyConsumerContract value) {
            return write("consumer-contract", value);
        }

        @Override
        public ReferenceAssemblyConsumerContract readConsumerContract(byte[] content) {
            return read("consumer-contract", content, ReferenceAssemblyConsumerContract.class);
        }

        @Override
        public byte[] writeManifest(ReferenceAssemblyManifest value) {
            return write("manifest", value);
        }

        @Override
        public ReferenceAssemblyManifest readManifest(byte[] content) {
            return read("manifest", content, ReferenceAssemblyManifest.class);
        }

        @Override
        public byte[] writeInspectionReport(ReferenceAssemblyInspectionReport value) {
            return write("inspection", value);
        }

        @Override
        public ReferenceAssemblyInspectionReport readInspectionReport(byte[] content) {
            return read("inspection", content, ReferenceAssemblyInspectionReport.class);
        }

        @Override
        public byte[] writeReleaseSubject(ReferenceAssemblyReleaseSubject value) {
            return write("release-subject", value);
        }

        @Override
        public ReferenceAssemblyReleaseSubject readReleaseSubject(byte[] content) {
            return read("release-subject", content, ReferenceAssemblyReleaseSubject.class);
        }

        @Override
        public byte[] writeReleaseManifest(ReferenceAssemblyReleaseManifest value) {
            return write("release-manifest", value);
        }

        @Override
        public ReferenceAssemblyReleaseManifest readReleaseManifest(byte[] content) {
            return read("release-manifest", content, ReferenceAssemblyReleaseManifest.class);
        }

        private byte[] write(String kind, Object value) {
            byte[] content = (kind + "|" + value).getBytes(StandardCharsets.UTF_8);
            decoded.put(key(content), value);
            return content;
        }

        private <T> T read(String kind, byte[] content, Class<T> type) {
            Object value = decoded.get(key(content));
            if (value == null
                    || !type.isInstance(value)
                    || !new String(content, StandardCharsets.UTF_8).startsWith(kind + "|")) {
                throw new IllegalArgumentException("not canonical test content");
            }
            return type.cast(value);
        }

        private static String key(byte[] content) {
            return Base64.getEncoder().encodeToString(content);
        }
    }

    private static ContentHash hash(String value) {
        return ReferenceAssemblyArtifactSupport.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
