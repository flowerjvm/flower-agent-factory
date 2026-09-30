package io.github.flowerjvm.factory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate;
import io.github.flowerjvm.factory.application.certification.ResolvedCertifiedAgentComponent;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyIntakeReceipt;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyProductLineCatalog;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.ProjectId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import io.github.flowerjvm.factory.infrastructure.referenceassembly.JacksonReferenceAssemblyArtifactCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Synthetic JDBC boundary fixtures, not a replacement for governed issuance or native acceptance evidence. */
class JdbcReferenceAssemblyIntakeTransactionTest {
    static final Instant NOW = Instant.parse("2026-09-01T00:00:10Z");

    @Test
    void acceptsOnlyTheExactCatalogGraphAndReobservesWithoutNewProductionEffects() throws Exception {
        var fixture = Fixture.create("exact");
        var before = fixture.certification.certifications().find(fixture.tenant(), fixture.component.certificationId()).orElseThrow();
        var transaction = fixture.transaction(fixture.gate(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(fixture.session, transaction.accept(fixture.session, fixture.component, fixture.staged));
        assertEquals(fixture.session, transaction.accept(fixture.session, fixture.component, fixture.staged));
        assertEquals(2, fixture.gateReads.get());
        assertEquals(1, fixture.raSessionCount());
        assertEquals(before, fixture.certification.certifications().find(fixture.tenant(), fixture.component.certificationId()).orElseThrow());
        assertEquals(0, fixture.count("factory_reference_assembly"));
        assertEquals(0, fixture.count("factory_decision_point"));
        assertTrue(ReferenceAssemblyIntakeReceipt.exact(fixture.receipt().orElseThrow(),
                ReferenceAssemblyIntakeReceipt.artifactFor(fixture.session, fixture.component)));
    }

    @Test
    void retryPreservesProgressAndOriginalTimesRatherThanReinitializingTheFlow() {
        var fixture = Fixture.create("progress");
        var transaction = fixture.transaction(fixture.gate(), Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));
        transaction.accept(fixture.session, fixture.component, fixture.staged);
        var progressed = fixture.session.advanceReferenceAssemblyPhase(BuildSessionPhase.UNDERSTAND_CUSTOMER,
                BuildSessionPhase.RESOLVE_REUSE_STRATEGY, NOW.plusSeconds(1));
        assertTrue(new JdbcBuildSessionRepository(fixture.dataSource).compareAndSet(fixture.session, progressed));
        BuildSession retry = fixture.request(fixture.session.buildSessionId(), fixture.session.projectId(),
                fixture.session.requestIdempotencyKey(), fixture.session.deadlineAt(), NOW.plusSeconds(2), fixture.tenant());
        assertEquals(progressed, transaction.accept(retry, fixture.component, fixture.stage(retry)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"session", "project", "deadline", "requirement"})
    void sameRequestKeyWithChangedPayloadCannotReturnOrOverwriteTheFirstSession(String field) throws Exception {
        var fixture = Fixture.create("changed-" + field);
        var transaction = fixture.transaction(fixture.gate(), Clock.fixed(NOW, ZoneOffset.UTC));
        transaction.accept(fixture.session, fixture.component, fixture.staged);
        var changed = fixture.request(field.equals("session") ? new BuildSessionId("other-ra") : fixture.session.buildSessionId(),
                field.equals("project") ? new ProjectId("other-project") : fixture.session.projectId(),
                fixture.session.requestIdempotencyKey(),
                field.equals("deadline") ? fixture.session.deadlineAt().plusSeconds(1) : fixture.session.deadlineAt(),
                NOW, fixture.tenant());
        var staged = fixture.stage(changed);
        if (field.equals("requirement")) {
            Artifact original = staged.stream().filter(value -> value.reference().equals(changed.requirementsArtifactRef())).findFirst().orElseThrow();
            staged = new ArrayList<>(staged);
            staged.remove(original);
            staged.add(new Artifact(original.tenantId(), original.reference(), hash("changed"), original.mediaType(), bytes("changed")));
        }
        var finalStaged = staged;
        assertThrows(RuntimeException.class, () -> transaction.accept(changed, fixture.component, finalStaged));
        assertEquals(1, fixture.raSessionCount());
        assertEquals(fixture.session, new JdbcBuildSessionRepository(fixture.dataSource)
                .find(fixture.tenant(), fixture.session.buildSessionId()).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "extra", "duplicate", "tenant", "hash", "bytes", "media", "oversize"})
    void rejectsNonExactStagedCatalogBeforeAnyDurableWrite(String change) throws Exception {
        var fixture = Fixture.create("stage-" + change);
        var altered = new ArrayList<>(fixture.staged);
        Artifact original = altered.get(0);
        if (change.equals("missing")) altered.remove(0);
        else if (change.equals("extra")) altered.add(new Artifact(fixture.tenant(), new ArtifactReference("extra"), hash("x"), "text/plain", bytes("x")));
        else if (change.equals("duplicate")) altered.set(1, original);
        else {
            altered.set(0, new Artifact(change.equals("tenant") ? new TenantId("wrong-tenant") : original.tenantId(),
                    original.reference(), change.equals("hash") ? hash("bad") : original.contentHash(),
                    change.equals("media") ? "text/plain" : original.mediaType(),
                    change.equals("oversize") ? new byte[1024 * 1024 + 1]
                            : change.equals("bytes") ? bytes("bad") : original.content()));
        }
        assertThrows(RuntimeException.class, () -> fixture.transaction(fixture.gate(), Clock.fixed(NOW, ZoneOffset.UTC))
                .accept(fixture.session, fixture.component, altered));
        fixture.assertNoIntake();
        assertEquals(0, fixture.gateReads.get());
    }

    @Test
    void componentReadGateFailureRollsBackTheNewReceiptAndSession() throws Exception {
        var fixture = Fixture.create("gate-denied");
        assertThrows(RuntimeException.class, () -> fixture.transaction((tenant, component) -> {
            throw new IllegalStateException("synthetic canonical evidence rejected");
        }, Clock.fixed(NOW, ZoneOffset.UTC)).accept(fixture.session, fixture.component, fixture.staged));
        fixture.assertNoIntake();
    }

    @Test
    void readGateSnapshotMustMatchTheLockedCertificationVersionExactly() throws Exception {
        var fixture = Fixture.create("stale-snapshot");
        var original = fixture.resolved();
        var stale = new ResolvedCertifiedAgentComponent(original.reference(), fixture.certification.requested(),
                original.inputLock(), original.evidence(), original.manifest(), original.compatibilityDescriptor(),
                original.candidate(), original.verificationRun());
        assertThrows(RuntimeException.class, () -> fixture.transaction((tenant, component) -> stale,
                Clock.fixed(NOW, ZoneOffset.UTC)).accept(fixture.session, fixture.component, fixture.staged));
        fixture.assertNoIntake();
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoked", "expired", "missing-tenant"})
    void rejectsNonCurrentOrOtherTenantCertificationBeforeTheReadGate(String condition) throws Exception {
        var fixture = Fixture.create("current-" + condition);
        Clock clock = Clock.fixed(condition.equals("expired") ? NOW.plusSeconds(3600) : NOW, ZoneOffset.UTC);
        if (condition.equals("revoked")) {
            var certified = fixture.certification.certified();
            assertTrue(fixture.certification.certifications().compareAndSet(certified, certified.revoke("REVOKED", NOW)));
        }
        var request = condition.equals("missing-tenant") ? fixture.request(fixture.session.buildSessionId(), fixture.session.projectId(),
                fixture.session.requestIdempotencyKey(), fixture.session.deadlineAt(), NOW, new TenantId("other-tenant")) : fixture.session;
        assertThrows(RuntimeException.class, () -> fixture.transaction(fixture.gate(), clock)
                .accept(request, fixture.component, fixture.stage(request)));
        fixture.assertNoIntake();
        assertEquals(0, fixture.gateReads.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"deadline", "certificate"})
    void rechecksTimeAfterTheFullEvidenceReadAndRollsBackOnExpiry(String boundary) throws Exception {
        var fixture = Fixture.create("read-expiry-" + boundary);
        var clock = new MutableClock(NOW);
        assertThrows(RuntimeException.class, () -> fixture.transaction((tenant, component) -> {
            clock.now = boundary.equals("deadline") ? fixture.session.deadlineAt()
                    : fixture.certification.certified().expiresAt().orElseThrow();
            return fixture.resolved();
        }, clock).accept(fixture.session, fixture.component, fixture.staged));
        fixture.assertNoIntake();
    }

    @Test
    void doesNotBackfillReceiptForAnExistingUnownedSession() throws Exception {
        var fixture = Fixture.create("orphan-session");
        fixture.staged.stream().filter(value -> !value.reference().equals(
                ReferenceAssemblyIntakeReceipt.reference(fixture.tenant(), fixture.session.requestIdempotencyKey())))
                .forEach(fixture.certification.artifacts()::store);
        new JdbcBuildSessionRepository(fixture.dataSource).create(fixture.session);
        assertThrows(RuntimeException.class, () -> fixture.transaction(fixture.gate(), Clock.fixed(NOW, ZoneOffset.UTC))
                .accept(fixture.session, fixture.component, fixture.staged));
        assertTrue(fixture.receipt().isEmpty());
        assertEquals(1, fixture.raSessionCount());
        assertEquals(0, fixture.gateReads.get());
    }

    @Test
    void doesNotRecreateMissingSessionForAnOrphanReceipt() throws Exception {
        var fixture = Fixture.create("orphan-receipt");
        fixture.certification.artifacts().store(ReferenceAssemblyIntakeReceipt.artifactFor(fixture.session, fixture.component));
        assertThrows(RuntimeException.class, () -> fixture.transaction(fixture.gate(), Clock.fixed(NOW, ZoneOffset.UTC))
                .accept(fixture.session, fixture.component, fixture.staged));
        assertEquals(0, fixture.raSessionCount());
        assertTrue(fixture.receipt().isPresent());
        assertEquals(0, fixture.gateReads.get());
    }

    @Test
    void laterArtifactConflictRollsBackAllNewCatalogWritesAndReceipt() throws Exception {
        var fixture = Fixture.create("artifact-conflict");
        Artifact target = fixture.staged.stream().filter(value -> value.reference().value().contains("host-fixture"))
                .findFirst().orElseThrow();
        fixture.certification.artifacts().store(new Artifact(target.tenantId(), target.reference(), hash("wrong"), target.mediaType(), bytes("wrong")));
        int before = fixture.count("factory_artifact");
        assertThrows(RuntimeException.class, () -> fixture.transaction(fixture.gate(), Clock.fixed(NOW, ZoneOffset.UTC))
                .accept(fixture.session, fixture.component, fixture.staged));
        fixture.assertNoIntake();
        assertEquals(before, fixture.count("factory_artifact"));
        assertEquals(1, fixture.gateReads.get());
    }

    @Test
    void concurrentExactRequestsCommitOneSessionAndReceipt() throws Exception {
        assertConcurrentExact(Fixture.create("concurrent"));
    }

    @Test
    void cancellationAfterAcceptanceCannotBeReenteredByAnIdenticalRetry() throws Exception {
        var fixture = Fixture.create("cancelled-retry");
        var transaction = fixture.transaction(fixture.gate(), Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));
        transaction.accept(fixture.session, fixture.component, fixture.staged);
        var cancellation = fixture.session.requestCancellation(NOW.plusSeconds(1));
        var sessions = new JdbcBuildSessionRepository(fixture.dataSource);
        assertTrue(sessions.compareAndSet(fixture.session, cancellation));
        assertThrows(RuntimeException.class, () -> transaction.accept(fixture.session, fixture.component, fixture.staged));
        assertEquals(cancellation, sessions.find(fixture.tenant(), fixture.session.buildSessionId()).orElseThrow());
        assertEquals(1, fixture.gateReads.get());
        assertEquals(1, fixture.raSessionCount());
    }

    @Test
    void deadlineExpiringDuringCatalogWritesRollsBackTheReceiptCatalogAndNewSession() throws Exception {
        var fixture = Fixture.create("write-expiry");
        var readComplete = new AtomicBoolean();
        var observationsAfterRead = new AtomicInteger();
        long newCatalogArtifacts = fixture.staged.stream()
                .filter(value -> !value.reference().equals(ReferenceAssemblyIntakeReceipt.reference(
                        fixture.tenant(), fixture.session.requestIdempotencyKey())))
                .filter(value -> fixture.certification.artifacts().find(fixture.tenant(), value.reference()).isEmpty()).count();
        assertTrue(newCatalogArtifacts > 0);
        // One post-read observation precedes the immutable store's timestamp per new artifact.
        // Time reaches the deadline during the final catalog insert, before the commit guard.
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() {
                return readComplete.get() && observationsAfterRead.incrementAndGet() >= newCatalogArtifacts + 1
                        ? fixture.session.deadlineAt() : NOW;
            }
        };
        int artifactsBefore = fixture.count("factory_artifact");
        assertThrows(RuntimeException.class, () -> fixture.transaction((tenant, component) -> {
            var resolved = fixture.resolved();
            readComplete.set(true);
            return resolved;
        }, clock).accept(fixture.session, fixture.component, fixture.staged));
        fixture.assertNoIntake();
        assertEquals(artifactsBefore, fixture.count("factory_artifact"));
    }

    static void assertConcurrentExact(Fixture fixture) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return fixture.transaction(fixture.gate(), Clock.fixed(NOW, ZoneOffset.UTC))
                        .accept(fixture.session, fixture.component, fixture.staged);
            });
            var second = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return fixture.transaction(fixture.gate(), Clock.fixed(NOW, ZoneOffset.UTC))
                        .accept(fixture.session, fixture.component, fixture.staged);
            });
            start.countDown();
            assertEquals(fixture.session, first.get(15, TimeUnit.SECONDS));
            assertEquals(fixture.session, second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, fixture.raSessionCount());
        assertTrue(fixture.receipt().isPresent());
        assertEquals(2, fixture.gateReads.get());
    }

    static final class Fixture {
        final DataSource dataSource;
        final JdbcCertificationRepositoryTest.Fixture certification;
        final CertifiedAgentComponentRef component;
        final BuildSession session;
        final List<Artifact> staged;
        final AtomicInteger gateReads = new AtomicInteger();

        static Fixture create(String suffix) {
            return new Fixture(FactoryDatabaseMigrationsTest.h2("reference_intake_" + suffix), suffix);
        }

        Fixture(DataSource dataSource, String suffix) {
            this.dataSource = dataSource;
            FactoryDatabaseMigrations.migrate(dataSource);
            var artifacts = new JdbcArtifactStore(dataSource);
            MaintenanceInvestigationProductContract.artifacts(PersistenceFixtures.TENANT).forEach(artifacts::store);
            certification = JdbcCertificationRepositoryTest.Fixture.createForVersionedComponentConsumer(dataSource, suffix,
                    MaintenanceInvestigationProductContract.GATE_PROFILE, MaintenanceInvestigationProductContract.lock(),
                    MaintenanceInvestigationProductContract.apiSignatureIndexLock(),
                    MaintenanceInvestigationProductContract.requirementTestMatrixLock());
            certification.certifications().create(certification.requested());
            assertTrue(certification.certifications().compareAndSet(certification.requested(), certification.certified()));
            var input = certification.inputLock();
            component = new CertifiedAgentComponentRef(CertifiedAgentComponentRef.SCHEMA_VERSION,
                    ReferenceAssemblyRequirement.COMPONENT_ROLE, ProductLineId.AGENT_PACK, input.artifactType(),
                    certification.certificationId(), certification.manifestLock(), input.candidateId(), input.candidateHash(),
                    input.sourceManifest(), certification.requested().inputLockArtifact(), input.verificationRunId(),
                    input.verificationResultManifest(), input.compatibilityDescriptor(), certification.evidenceLock(), input.certificationProfile());
            session = request(new BuildSessionId("ra-intake-" + suffix), new ProjectId("ra-project-" + suffix),
                    "ra-intake-request-" + suffix, NOW.plusSeconds(600), NOW, tenant());
            staged = stage(session);
        }

        TenantId tenant() { return certification.tenant(); }

        BuildSession request(BuildSessionId id, ProjectId project, String key, Instant deadline, Instant created, TenantId tenant) {
            var scratch = new StagingStore();
            var requirement = new ReferenceAssemblyProductLineCatalog(scratch, new JacksonReferenceAssemblyArtifactCodec())
                    .stageRequirement(tenant, component, ReferenceAssemblyProductLineCatalog.Entry.MAINTENANCE_INVESTIGATION_V1);
            return new BuildSession(id, tenant, project, ProductLineId.REFERENCE_ASSEMBLY, key, "factory-builder",
                    BuildSessionStatus.RUNNING, BuildSessionPhase.UNDERSTAND_CUSTOMER, requirement.reference(), requirement.hash(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    0, 0, created, deadline, Optional.empty(), Optional.empty(), Optional.empty(), 0, created, created);
        }

        List<Artifact> stage(BuildSession request) {
            var scratch = new StagingStore();
            new ReferenceAssemblyProductLineCatalog(scratch, new JacksonReferenceAssemblyArtifactCodec())
                    .stageRequirement(request.tenantId(), component, ReferenceAssemblyProductLineCatalog.Entry.MAINTENANCE_INVESTIGATION_V1);
            scratch.store(ReferenceAssemblyIntakeReceipt.artifactFor(request, component));
            return List.copyOf(scratch.values.values());
        }

        CertifiedAgentComponentReadGate gate() {
            return (tenant, reference) -> {
                assertEquals(tenant(), tenant);
                assertEquals(component, reference);
                gateReads.incrementAndGet();
                return resolved();
            };
        }

        ResolvedCertifiedAgentComponent resolved() {
            var codec = certification.codec();
            var input = certification.inputLock();
            return new ResolvedCertifiedAgentComponent(component, certification.certifications()
                    .find(tenant(), certification.certificationId()).orElseThrow(), input,
                    codec.readEvidence(content(certification.evidenceLock())),
                    codec.readComponentManifest(content(certification.manifestLock())),
                    codec.readCompatibilityDescriptor(content(input.compatibilityDescriptor())),
                    new JdbcCandidateVersionRepository(dataSource).find(tenant(), input.candidateId()).orElseThrow(),
                    new JdbcVerificationRunRepository(dataSource).find(tenant(), input.verificationRunId()).orElseThrow());
        }

        byte[] content(CertificationArtifactLock lock) {
            return certification.artifacts().find(tenant(), lock.reference()).orElseThrow().content();
        }

        JdbcReferenceAssemblyIntakeTransaction transaction(CertifiedAgentComponentReadGate gate, Clock clock) {
            return new JdbcReferenceAssemblyIntakeTransaction(dataSource, new ObjectMapper(), gate, clock);
        }

        Optional<Artifact> receipt() {
            return certification.artifacts().find(tenant(),
                    ReferenceAssemblyIntakeReceipt.reference(tenant(), session.requestIdempotencyKey()));
        }

        void assertNoIntake() throws Exception {
            assertTrue(receipt().isEmpty());
            assertEquals(0, raSessionCount());
        }

        int raSessionCount() throws Exception {
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM factory_build_session WHERE product_line_id = 'reference-assembly'");
                 var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
        }

        int count(String table) throws Exception {
            if (!List.of("factory_artifact", "factory_reference_assembly", "factory_decision_point").contains(table)) {
                throw new IllegalArgumentException("test table must be allowlisted");
            }
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                 var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
        }
    }

    static final class StagingStore implements ArtifactStore {
        final Map<ArtifactReference, Artifact> values = new LinkedHashMap<>();
        @Override public ArtifactReference store(Artifact artifact) { values.put(artifact.reference(), artifact); return artifact.reference(); }
        @Override public Optional<Artifact> find(TenantId tenant, ArtifactReference ref) {
            return Optional.ofNullable(values.get(ref)).filter(value -> value.tenantId().equals(tenant));
        }
    }

    private static final class MutableClock extends Clock {
        volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static ContentHash hash(String text) {
        try { return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(text)))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
