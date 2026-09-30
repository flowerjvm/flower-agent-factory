package io.github.flowerjvm.factory.application.referenceassembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionRepository;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.certification.CertifiedArtifactType;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CandidateId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.ids.VerificationRunId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyConsumerContract;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyInspectionReport;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseManifest;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyReleaseSubject;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ReferenceAssemblyRequestServiceTest {
    @Test
    void requestSelectsEachExactConsumerEntryFromItsDurableRequirement() {
        for (var entry : ReferenceAssemblyProductLineCatalog.Entry.values()) {
            Fixture fixture = new Fixture();
            var catalog = new ReferenceAssemblyProductLineCatalog(fixture.store, fixture.codec);
            var requirement = catalog.stageRequirement(fixture.tenant, fixture.component, entry);
            fixture.buildSessions.session = fixture.session(requirement, ProductLineId.REFERENCE_ASSEMBLY,
                    BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER,
                    Fixture.NOW.plusSeconds(600), Optional.empty(), Optional.empty());
            var service = new ReferenceAssemblyRequestService(fixture.buildSessions, fixture.referenceAssemblies,
                    fixture.store, fixture.codec, catalog.admissionPolicies(), fixture.clock);

            var requested = service.ensureRequested(fixture.tenant, fixture.buildSessionId).referenceAssembly();

            assertEquals(catalog.consumerContractLock(entry), requested.consumerContract());
            assertEquals(catalog.policySnapshotLock(entry), requested.policySnapshot());
            assertEquals(requirement, requested.requirement());
            assertEquals(1, fixture.referenceAssemblies.createCalls.get());
        }
    }

    @Test
    void requestRejectsKnownConsumerWithPolicyFromAnotherEntryBeforeCreatingLedger() {
        Fixture fixture = new Fixture();
        var catalog = new ReferenceAssemblyProductLineCatalog(fixture.store, fixture.codec);
        var entry = ReferenceAssemblyProductLineCatalog.Entry.MAINTENANCE_INVESTIGATION_V1;
        catalog.provisionTenant(fixture.tenant);
        catalog.provisionTenant(fixture.tenant, entry);
        var requirement = fixture.stageRequirement(catalog.consumerContractLock(entry),
                catalog.hostFixtureLock(entry), catalog.policySnapshotLock());
        fixture.buildSessions.session = fixture.session(requirement, ProductLineId.REFERENCE_ASSEMBLY,
                BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER,
                Fixture.NOW.plusSeconds(600), Optional.empty(), Optional.empty());
        var service = new ReferenceAssemblyRequestService(fixture.buildSessions, fixture.referenceAssemblies,
                fixture.store, fixture.codec, catalog.admissionPolicies(), fixture.clock);

        assertCode(ReferenceAssemblyRequestService.ADMISSION_REJECTED,
                () -> service.ensureRequested(fixture.tenant, fixture.buildSessionId));
        assertEquals(0, fixture.referenceAssemblies.createCalls.get());
    }

    @Test
    void createsDeterministicRequestedLedgerFromTrustedExactGraph() {
        Fixture fixture = new Fixture();

        ReferenceAssemblyRequestOutcome outcome = fixture.service().ensureRequested(
                fixture.tenant, fixture.buildSessionId);

        ReferenceAssembly requested = outcome.referenceAssembly();
        assertEquals(ReferenceAssemblyRequestDisposition.CREATED, outcome.disposition());
        assertEquals(ReferenceAssemblyStatus.REQUESTED, requested.status());
        assertEquals(0, requested.version());
        assertEquals(Fixture.NOW, requested.createdAt());
        assertEquals(
                ReferenceAssemblyRequestService.deriveId(
                        fixture.tenant, fixture.buildSessionId),
                requested.referenceAssemblyId());
        assertNotEquals(
                requested.referenceAssemblyId(),
                ReferenceAssemblyRequestService.deriveId(
                        new TenantId("another-tenant"), fixture.buildSessionId));
        assertEquals(fixture.requirementLock, requested.requirement());
        assertEquals(fixture.component.certificationId(), requested.componentCertificationId());
        assertEquals(fixture.component.candidateHash(), requested.componentCandidateHash());
        assertEquals(
                fixture.component.certificationManifest(),
                requested.componentCertificationManifest());
        assertTrue(fixture.store.reads(fixture.requirementLock) >= 1);
        assertTrue(fixture.store.reads(fixture.consumerContractLock) >= 1);
        assertTrue(fixture.store.reads(fixture.hostFixture) >= 1);
        assertTrue(fixture.store.reads(fixture.policySnapshot) >= 1);
    }

    @Test
    void retryWithLaterClockReturnsOnlyTheExactStoredRequest() {
        Fixture fixture = new Fixture();
        ReferenceAssemblyRequestOutcome first = fixture.service().ensureRequested(
                fixture.tenant, fixture.buildSessionId);

        ReferenceAssemblyRequestOutcome retry = fixture.service(Clock.fixed(
                        Fixture.NOW.plusSeconds(10), ZoneOffset.UTC))
                .ensureRequested(fixture.tenant, fixture.buildSessionId);

        assertEquals(ReferenceAssemblyRequestDisposition.EXISTING_EXACT, retry.disposition());
        assertEquals(first.referenceAssembly(), retry.referenceAssembly());
        assertEquals(1, fixture.referenceAssemblies.createCalls.get());
    }

    @Test
    void exactDuplicateCreateRaceConvergesWithoutASecondLedger() {
        Fixture fixture = new Fixture();
        fixture.referenceAssemblies.createReplacement = Function.identity();
        fixture.referenceAssemblies.throwAfterCreate = true;

        ReferenceAssemblyRequestOutcome outcome = fixture.service().ensureRequested(
                fixture.tenant, fixture.buildSessionId);

        assertEquals(ReferenceAssemblyRequestDisposition.EXISTING_EXACT, outcome.disposition());
        assertEquals(1, fixture.referenceAssemblies.createCalls.get());
    }

    @Test
    void duplicateCreateRaceRejectsAStoredRowWithDifferentImmutableComponentLock() {
        Fixture fixture = new Fixture();
        fixture.referenceAssemblies.createReplacement = requested -> ReferenceAssembly.requested(
                requested.referenceAssemblyId(),
                requested.tenantId(),
                requested.buildSessionId(),
                requested.requirement(),
                requested.consumerContract(),
                requested.hostFixture(),
                requested.policySnapshot(),
                requested.componentCertificationId(),
                requested.componentCandidateHash(),
                fixture.lock("conflicting-certification-manifest"),
                requested.createdAt());
        fixture.referenceAssemblies.throwAfterCreate = true;

        assertCode(
                ReferenceAssemblyRequestService.REPOSITORY_CONFLICT,
                () -> fixture.service().ensureRequested(fixture.tenant, fixture.buildSessionId));
    }

    @Test
    void rejectsTamperedRequirementBytesBeforeCreatingAnyLedger() {
        Fixture fixture = new Fixture();
        fixture.store.replaceContentKeepingDeclaredHash(
                fixture.tenant,
                fixture.requirementLock,
                "tampered".getBytes(StandardCharsets.UTF_8));

        assertCode(
                ReferenceAssemblyRequestService.ARTIFACT_INVALID,
                () -> fixture.service().ensureRequested(fixture.tenant, fixture.buildSessionId));
        assertEquals(0, fixture.referenceAssemblies.createCalls.get());
    }

    @Test
    void rejectsTamperedConsumerHostAndPolicyArtifactsIndependently() {
        Fixture consumer = new Fixture();
        consumer.store.replaceContentKeepingDeclaredHash(
                consumer.tenant,
                consumer.consumerContractLock,
                "tampered-consumer".getBytes(StandardCharsets.UTF_8));
        assertCode(
                ReferenceAssemblyRequestService.ARTIFACT_INVALID,
                () -> consumer.service().ensureRequested(consumer.tenant, consumer.buildSessionId));

        Fixture host = new Fixture();
        host.store.replaceContentKeepingDeclaredHash(
                host.tenant,
                host.hostFixture,
                "tampered-host".getBytes(StandardCharsets.UTF_8));
        assertCode(
                ReferenceAssemblyRequestService.ARTIFACT_INVALID,
                () -> host.service().ensureRequested(host.tenant, host.buildSessionId));

        Fixture policy = new Fixture();
        policy.store.replaceContentKeepingDeclaredHash(
                policy.tenant,
                policy.policySnapshot,
                "tampered-policy".getBytes(StandardCharsets.UTF_8));
        assertCode(
                ReferenceAssemblyRequestService.ARTIFACT_INVALID,
                () -> policy.service().ensureRequested(policy.tenant, policy.buildSessionId));
    }

    @Test
    void rejectsSelfDeclaredConsumerContractOutsideCodeOwnedAdmissionPolicy() {
        Fixture fixture = new Fixture();
        CertificationArtifactLock selfDeclaredContract = fixture.stageCanonical(
                "self-declared-contract", fixture.codec.writeConsumerContract(fixture.contract));
        CertificationArtifactLock selfDeclaredRequirement = fixture.stageRequirement(
                selfDeclaredContract, fixture.hostFixture, fixture.policySnapshot);
        fixture.buildSessions.session = fixture.session(
                selfDeclaredRequirement,
                ProductLineId.REFERENCE_ASSEMBLY,
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                Fixture.NOW.plusSeconds(600),
                Optional.empty(),
                Optional.empty());

        assertCode(
                ReferenceAssemblyRequestService.ADMISSION_REJECTED,
                () -> fixture.service().ensureRequested(fixture.tenant, fixture.buildSessionId));
    }

    @Test
    void rejectsConsumerContractWhoseProductLockDiffersFromTrustedPolicy() {
        Fixture fixture = new Fixture();
        ReferenceAssemblyConsumerContract selfDeclared = fixture.contract(
                fixture.lock("different-agent-product-contract"),
                fixture.apiSignature,
                fixture.hostFixture);
        CertificationArtifactLock selfDeclaredContract = fixture.stageCanonical(
                "different-product-contract", fixture.codec.writeConsumerContract(selfDeclared));
        CertificationArtifactLock requirement = fixture.stageRequirement(
                selfDeclaredContract, fixture.hostFixture, fixture.policySnapshot);
        fixture.buildSessions.session = fixture.session(
                requirement,
                ProductLineId.REFERENCE_ASSEMBLY,
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                Fixture.NOW.plusSeconds(600),
                Optional.empty(),
                Optional.empty());

        assertCode(
                ReferenceAssemblyRequestService.ADMISSION_REJECTED,
                () -> fixture.service(fixture.policy(selfDeclaredContract), fixture.clock)
                        .ensureRequested(fixture.tenant, fixture.buildSessionId));
    }

    @Test
    void rejectsExpiredOrWrongProductLineSessionBeforeReadingArtifacts() {
        Fixture expired = new Fixture();
        expired.buildSessions.session = expired.session(
                expired.requirementLock,
                ProductLineId.REFERENCE_ASSEMBLY,
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                Fixture.NOW,
                Optional.empty(),
                Optional.empty());
        assertCode(
                ReferenceAssemblyRequestService.SESSION_NOT_ELIGIBLE,
                () -> expired.service().ensureRequested(expired.tenant, expired.buildSessionId));
        assertEquals(0, expired.store.totalReads());

        Fixture wrongLine = new Fixture();
        wrongLine.store.clearReads();
        wrongLine.buildSessions.session = wrongLine.session(
                wrongLine.requirementLock,
                ProductLineId.AGENT_PACK,
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                Fixture.NOW.plusSeconds(600),
                Optional.empty(),
                Optional.empty());
        assertCode(
                ReferenceAssemblyRequestService.SESSION_NOT_ELIGIBLE,
                () -> wrongLine.service().ensureRequested(wrongLine.tenant, wrongLine.buildSessionId));
        assertEquals(0, wrongLine.store.totalReads());
    }

    @Test
    void rejectsCancellationOrExistingCertificationBeforeReadingArtifacts() {
        Fixture cancelling = new Fixture();
        cancelling.store.clearReads();
        cancelling.buildSessions.session = cancelling.session(
                cancelling.requirementLock,
                ProductLineId.REFERENCE_ASSEMBLY,
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                Fixture.NOW.plusSeconds(600),
                Optional.of(Fixture.NOW.minusSeconds(1)),
                Optional.empty());
        assertCode(
                ReferenceAssemblyRequestService.SESSION_NOT_ELIGIBLE,
                () -> cancelling.service().ensureRequested(
                        cancelling.tenant, cancelling.buildSessionId));
        assertEquals(0, cancelling.store.totalReads());

        Fixture alreadyCertified = new Fixture();
        alreadyCertified.store.clearReads();
        alreadyCertified.buildSessions.session = alreadyCertified.session(
                alreadyCertified.requirementLock,
                ProductLineId.REFERENCE_ASSEMBLY,
                BuildSessionStatus.RUNNING,
                BuildSessionPhase.UNDERSTAND_CUSTOMER,
                Fixture.NOW.plusSeconds(600),
                Optional.empty(),
                Optional.of(new CertificationId("whole-product-certification")));
        assertCode(
                ReferenceAssemblyRequestService.SESSION_NOT_ELIGIBLE,
                () -> alreadyCertified.service().ensureRequested(
                        alreadyCertified.tenant, alreadyCertified.buildSessionId));
        assertEquals(0, alreadyCertified.store.totalReads());
    }

    @Test
    void retryObservesExactProgressedAssemblyWithoutRecreatingRequest() {
        Fixture fixture = new Fixture();
        ReferenceAssembly requested = fixture.service().ensureRequested(
                fixture.tenant, fixture.buildSessionId).referenceAssembly();
        ReferenceAssembly progressed = requested
                .resolveComponent(Fixture.NOW.plusSeconds(1))
                .assemble(fixture.lock("assembly-manifest"), Fixture.NOW.plusSeconds(2));
        fixture.referenceAssemblies.replace(progressed);

        ReferenceAssemblyRequestOutcome retry = fixture.service(Clock.fixed(
                        Fixture.NOW.plusSeconds(3), ZoneOffset.UTC))
                .ensureRequested(fixture.tenant, fixture.buildSessionId);

        assertEquals(ReferenceAssemblyRequestDisposition.EXISTING_EXACT, retry.disposition());
        assertEquals(ReferenceAssemblyStatus.ASSEMBLED, retry.referenceAssembly().status());
        assertEquals(progressed, retry.referenceAssembly());
        assertEquals(1, fixture.referenceAssemblies.createCalls.get());
    }

    @Test
    void retryObservesExactReleasedAssemblyWithoutRecreatingRequest() {
        Fixture fixture = new Fixture();
        ReferenceAssembly requested = fixture.service().ensureRequested(
                fixture.tenant, fixture.buildSessionId).referenceAssembly();
        DecisionPointId decisionPoint = new DecisionPointId("reference-release-decision");
        ContentHash subject = hash("reference-release-subject");
        ReferenceAssembly released = requested
                .resolveComponent(Fixture.NOW.plusSeconds(1))
                .assemble(fixture.lock("assembly-manifest"), Fixture.NOW.plusSeconds(2))
                .inspect(fixture.lock("inspection-report"), Fixture.NOW.plusSeconds(3))
                .bindReleaseReview(decisionPoint, subject, Fixture.NOW.plusSeconds(4))
                .bindReleaseAction("release-action-run", Fixture.NOW.plusSeconds(5))
                .release(
                        fixture.lock("release-manifest"),
                        decisionPoint,
                        subject,
                        "release-action-run",
                        Fixture.NOW.plusSeconds(6));
        fixture.referenceAssemblies.replace(released);

        ReferenceAssemblyRequestOutcome retry = fixture.service(Clock.fixed(
                        Fixture.NOW.plusSeconds(7), ZoneOffset.UTC))
                .ensureRequested(fixture.tenant, fixture.buildSessionId);

        assertEquals(ReferenceAssemblyRequestDisposition.EXISTING_EXACT, retry.disposition());
        assertEquals(ReferenceAssemblyStatus.RELEASED, retry.referenceAssembly().status());
        assertEquals(released, retry.referenceAssembly());
        assertEquals(1, fixture.referenceAssemblies.createCalls.get());
    }

    @Test
    void retryObservesExactRejectedAssemblyWithoutRecreatingRequest() {
        Fixture fixture = new Fixture();
        ReferenceAssembly requested = fixture.service().ensureRequested(
                fixture.tenant, fixture.buildSessionId).referenceAssembly();
        ReferenceAssembly rejected = requested.reject(
                "REFERENCE_ASSEMBLY_POLICY_REJECTED", Fixture.NOW.plusSeconds(1));
        fixture.referenceAssemblies.replace(rejected);

        ReferenceAssemblyRequestOutcome retry = fixture.service(Clock.fixed(
                        Fixture.NOW.plusSeconds(2), ZoneOffset.UTC))
                .ensureRequested(fixture.tenant, fixture.buildSessionId);

        assertEquals(ReferenceAssemblyRequestDisposition.EXISTING_EXACT, retry.disposition());
        assertEquals(ReferenceAssemblyStatus.REJECTED, retry.referenceAssembly().status());
        assertEquals(rejected, retry.referenceAssembly());
        assertEquals(1, fixture.referenceAssemblies.createCalls.get());
    }

    private static void assertCode(String expected, Runnable work) {
        ReferenceAssemblyRequestException failure =
                assertThrows(ReferenceAssemblyRequestException.class, work::run);
        assertEquals(expected, failure.code());
    }

    private static final class Fixture {
        private static final Instant NOW = Instant.parse("2026-09-02T03:04:05.123456Z");
        private final TenantId tenant = new TenantId("tenant-reference-request");
        private final BuildSessionId buildSessionId = new BuildSessionId("build-reference-request");
        private final MemoryArtifactStore store = new MemoryArtifactStore();
        private final TestCodec codec = new TestCodec();
        private final CertificationArtifactLock hostFixture =
                stageOpaque("host-fixture", "trusted-host-fixture");
        private final CertificationArtifactLock policySnapshot =
                stageOpaque("policy-snapshot", "trusted-policy");
        private final CertificationArtifactLock productContract = lock("agent-product-contract");
        private final CertificationArtifactLock apiSignature = lock("agent-api-signature");
        private final CertifiedAgentComponentRef component = component();
        private final ReferenceAssemblyConsumerContract contract = contract(
                productContract, apiSignature, hostFixture);
        private final CertificationArtifactLock consumerContractLock = stageCanonical(
                "consumer-contract", codec.writeConsumerContract(contract));
        private final CertificationArtifactLock requirementLock = stageRequirement(
                consumerContractLock, hostFixture, policySnapshot);
        private final MemoryBuildSessionRepository buildSessions =
                new MemoryBuildSessionRepository(session(
                        requirementLock,
                        ProductLineId.REFERENCE_ASSEMBLY,
                        BuildSessionStatus.RUNNING,
                        BuildSessionPhase.UNDERSTAND_CUSTOMER,
                        NOW.plusSeconds(600),
                        Optional.empty(),
                        Optional.empty()));
        private final MemoryReferenceAssemblyRepository referenceAssemblies =
                new MemoryReferenceAssemblyRepository();
        private final ReferenceAssemblyAdmissionPolicy admissionPolicy =
                policy(consumerContractLock);
        private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        private ReferenceAssemblyRequestService service() {
            return service(admissionPolicy, clock);
        }

        private ReferenceAssemblyRequestService service(Clock requestedClock) {
            return service(admissionPolicy, requestedClock);
        }

        private ReferenceAssemblyRequestService service(
                ReferenceAssemblyAdmissionPolicy policy, Clock requestedClock) {
            return new ReferenceAssemblyRequestService(
                    buildSessions,
                    referenceAssemblies,
                    store,
                    codec,
                    policy,
                    requestedClock);
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
                    "factory.ordinal-sha256.v1",
                    ReferenceAssemblyConsumerContract.ASSEMBLY_ALGORITHM_ID);
        }

        private BuildSession session(
                CertificationArtifactLock requirements,
                ProductLineId productLine,
                BuildSessionStatus status,
                BuildSessionPhase phase,
                Instant deadline,
                Optional<Instant> cancellation,
                Optional<CertificationId> currentCertification) {
            return new BuildSession(
                    buildSessionId,
                    tenant,
                    new ProjectId("project-reference-request"),
                    productLine,
                    "request-reference-assembly",
                    "reference-requester",
                    status,
                    phase,
                    requirements.reference(),
                    requirements.hash(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    currentCertification,
                    0,
                    0,
                    NOW.minusSeconds(60),
                    deadline,
                    cancellation,
                    Optional.empty(),
                    Optional.empty(),
                    3,
                    NOW.minusSeconds(60),
                    NOW.minusSeconds(1));
        }

        private CertificationArtifactLock stageRequirement(
                CertificationArtifactLock consumer,
                CertificationArtifactLock host,
                CertificationArtifactLock policy) {
            ReferenceAssemblyRequirement requirement = new ReferenceAssemblyRequirement(
                    ReferenceAssemblyRequirement.SCHEMA_VERSION,
                    ReferenceAssemblyRequirement.PRODUCT_LINE_ID,
                    consumer,
                    host,
                    policy,
                    component);
            return stageCanonical("requirement", codec.writeRequirement(requirement));
        }

        private ReferenceAssemblyConsumerContract contract(
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

        private CertifiedAgentComponentRef component() {
            return new CertifiedAgentComponentRef(
                    CertifiedAgentComponentRef.SCHEMA_VERSION,
                    ReferenceAssemblyRequirement.COMPONENT_ROLE,
                    ProductLineId.AGENT_PACK,
                    CertifiedArtifactType.AGENT_PACK,
                    new CertificationId("certification-reference-component"),
                    lock("certification-manifest"),
                    new CandidateId("candidate-reference-component"),
                    hash("candidate-reference-component"),
                    lock("source-manifest"),
                    lock("input-lock-manifest"),
                    new VerificationRunId("verification-reference-component"),
                    lock("verification-result"),
                    lock("compatibility-descriptor"),
                    lock("certification-evidence"),
                    ReferenceAssemblyConsumerContract.REQUIRED_CERTIFICATION_PROFILE);
        }

        private CertificationArtifactLock stageOpaque(String name, String value) {
            return stage(name, "application/octet-stream", value.getBytes(StandardCharsets.UTF_8));
        }

        private CertificationArtifactLock stageCanonical(String name, byte[] content) {
            return stage(name, ReferenceAssemblyArtifactCodec.MEDIA_TYPE, content);
        }

        private CertificationArtifactLock stage(String name, String mediaType, byte[] content) {
            ContentHash contentHash = ReferenceAssemblyArtifactSupport.sha256(content);
            ArtifactReference reference = new ArtifactReference(
                    "test/reference-request/" + name + "/sha256/" + contentHash.sha256());
            store.store(new Artifact(tenant, reference, contentHash, mediaType, content));
            return new CertificationArtifactLock(reference, contentHash);
        }

        private CertificationArtifactLock lock(String name) {
            ContentHash contentHash = hash(name);
            return new CertificationArtifactLock(
                    new ArtifactReference("locked/" + name + "/sha256/" + contentHash.sha256()),
                    contentHash);
        }
    }

    private static final class MemoryBuildSessionRepository implements BuildSessionRepository {
        private BuildSession session;

        private MemoryBuildSessionRepository(BuildSession session) {
            this.session = session;
        }

        @Override
        public void create(BuildSession ignored) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<BuildSession> find(TenantId tenantId, BuildSessionId buildSessionId) {
            return Optional.ofNullable(session);
        }

        @Override
        public boolean compareAndSet(BuildSession expected, BuildSession next) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MemoryReferenceAssemblyRepository
            implements ReferenceAssemblyRepository {
        private final Map<String, ReferenceAssembly> byId = new LinkedHashMap<>();
        private final Map<String, ReferenceAssembly> byBuild = new LinkedHashMap<>();
        private final AtomicInteger createCalls = new AtomicInteger();
        private Function<ReferenceAssembly, ReferenceAssembly> createReplacement;
        private boolean throwAfterCreate;

        @Override
        public void create(ReferenceAssembly assembly) {
            createCalls.incrementAndGet();
            ReferenceAssembly stored = createReplacement == null
                    ? assembly
                    : createReplacement.apply(assembly);
            replace(stored);
            if (throwAfterCreate) {
                throw new IllegalStateException("simulated duplicate race");
            }
        }

        @Override
        public Optional<ReferenceAssembly> find(
                TenantId tenantId, ReferenceAssemblyId referenceAssemblyId) {
            return Optional.ofNullable(byId.get(idKey(tenantId, referenceAssemblyId)));
        }

        @Override
        public Optional<ReferenceAssembly> findByBuildSession(
                TenantId tenantId, BuildSessionId buildSessionId) {
            return Optional.ofNullable(byBuild.get(buildKey(tenantId, buildSessionId)));
        }

        @Override
        public List<ReferenceAssembly> findReleasedByComponentCertification(
                TenantId tenantId, CertificationId componentCertificationId) {
            return List.of();
        }

        @Override
        public boolean compareAndSet(ReferenceAssembly expected, ReferenceAssembly next) {
            throw new UnsupportedOperationException();
        }

        private void replace(ReferenceAssembly assembly) {
            byId.put(idKey(assembly.tenantId(), assembly.referenceAssemblyId()), assembly);
            byBuild.put(buildKey(assembly.tenantId(), assembly.buildSessionId()), assembly);
        }

        private static String idKey(TenantId tenantId, ReferenceAssemblyId id) {
            return tenantId.value() + "\u0000" + id.value();
        }

        private static String buildKey(TenantId tenantId, BuildSessionId id) {
            return tenantId.value() + "\u0000" + id.value();
        }
    }

    private static final class MemoryArtifactStore implements ArtifactStore {
        private final Map<String, Artifact> values = new LinkedHashMap<>();
        private final Map<String, AtomicInteger> reads = new LinkedHashMap<>();

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
            return artifact.reference();
        }

        @Override
        public Optional<Artifact> find(TenantId tenantId, ArtifactReference reference) {
            reads.computeIfAbsent(key(tenantId, reference), ignored -> new AtomicInteger())
                    .incrementAndGet();
            return Optional.ofNullable(values.get(key(tenantId, reference)));
        }

        private int reads(CertificationArtifactLock lock) {
            return reads.getOrDefault(
                    key(new TenantId("tenant-reference-request"), lock.reference()),
                    new AtomicInteger()).get();
        }

        private int totalReads() {
            return reads.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        private void clearReads() {
            reads.clear();
        }

        private void replaceContentKeepingDeclaredHash(
                TenantId tenantId, CertificationArtifactLock lock, byte[] content) {
            Artifact existing = values.get(key(tenantId, lock.reference()));
            values.put(
                    key(tenantId, lock.reference()),
                    new Artifact(
                            tenantId,
                            existing.reference(),
                            existing.contentHash(),
                            existing.mediaType(),
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
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceAssemblyManifest readManifest(byte[] content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] writeInspectionReport(ReferenceAssemblyInspectionReport value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceAssemblyInspectionReport readInspectionReport(byte[] content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] writeReleaseSubject(ReferenceAssemblyReleaseSubject value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceAssemblyReleaseSubject readReleaseSubject(byte[] content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] writeReleaseManifest(ReferenceAssemblyReleaseManifest value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceAssemblyReleaseManifest readReleaseManifest(byte[] content) {
            throw new UnsupportedOperationException();
        }

        private byte[] write(String kind, Object value) {
            byte[] content = (kind + "|" + value).getBytes(StandardCharsets.UTF_8);
            decoded.put(key(content), value);
            return content;
        }

        private <T> T read(String kind, byte[] content, Class<T> type) {
            Object value = decoded.get(key(content));
            if (!type.isInstance(value)
                    || !new String(content, StandardCharsets.UTF_8).startsWith(kind + "|")) {
                throw new IllegalArgumentException("not strict canonical test content");
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
