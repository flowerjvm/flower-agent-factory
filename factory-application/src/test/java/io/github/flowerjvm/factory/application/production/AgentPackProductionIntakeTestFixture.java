package io.github.flowerjvm.factory.application.production;

import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.candidate.CandidateVersionRepository;
import io.github.flowerjvm.factory.application.decision.*;
import io.github.flowerjvm.factory.application.verification.*;
import io.github.flowerjvm.factory.application.work.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.factory.contracts.worker.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Real intake and production services; only persistence, encoding and unused phase ports are test doubles. */
final class AgentPackProductionIntakeTestFixture {
    static final Instant NOW = Instant.parse("2026-09-06T03:30:00.123456789Z");
    static final TenantId TENANT = new TenantId("intake-tenant");
    final MutableClock clock = new MutableClock(NOW);
    final Sessions sessions = new Sessions();
    final AtomicInteger inputReads = new AtomicInteger();
    final AtomicInteger commits = new AtomicInteger();
    final AtomicInteger acceptanceAttempts = new AtomicInteger();
    final Map<String, BuildSession> receipts = new HashMap<>();
    final Map<String, List<Artifact>> acceptedArtifacts = new HashMap<>();
    final Map<String, Artifact> storedArtifacts = new HashMap<>();
    Runnable duringInputs = () -> {};
    RuntimeException inputFailure;
    AgentPackProductionIntakeInputs.Bundle alternateBundle;

    final ArtifactStore artifacts = new ArtifactStore() {
        public ArtifactReference store(Artifact artifact) {
            throw new AssertionError("intake service may only stage artifacts through the acceptance transaction");
        }
        public Optional<Artifact> find(TenantId tenant, ArtifactReference reference) {
            return Optional.ofNullable(storedArtifacts.get(tenant.value() + "\n" + reference.value()));
        }
    };

    final AgentPackWorkPreparationTransaction transaction = new AgentPackWorkPreparationTransaction() {
        @Override public synchronized BuildSession accept(BuildSession pristine, List<Artifact> staged) {
            acceptanceAttempts.incrementAndGet();
            String key = pristine.tenantId().value() + "\n" + pristine.requestIdempotencyKey();
            BuildSession receipt = receipts.get(key);
            if (receipt != null) {
                AgentPackProductionIntakeAuthority.requireExact(receipt, pristine.tenantId(), pristine.createdBy(),
                        pristine.requestIdempotencyKey(), input(pristine));
                if (!receipt.requirementsHash().equals(pristine.requirementsHash())
                        || !receipt.selectedManagerWorkerBinding().equals(pristine.selectedManagerWorkerBinding())
                        || !receipt.selectedCodingWorkerBinding().equals(pristine.selectedCodingWorkerBinding())
                        || !locks(acceptedArtifacts.get(key)).equals(locks(staged))) {
                    throw new IllegalArgumentException("immutable acceptance mismatch");
                }
                return sessions.find(pristine.tenantId(), pristine.buildSessionId()).orElseThrow();
            }
            if (sessions.find(pristine.tenantId(), pristine.buildSessionId()).isPresent()) {
                throw new IllegalArgumentException("session conflict");
            }
            sessions.create(pristine); receipts.put(key, pristine); acceptedArtifacts.put(key, List.copyOf(staged));
            staged.forEach(artifact -> storedArtifacts.put(artifact.tenantId().value() + "\n" + artifact.reference().value(), artifact));
            commits.incrementAndGet(); return pristine;
        }
        @Override public PreparationOutcome prepare(BuildSession expected, WorkOrder order, WorkerRunRecord run, List<Artifact> staged) {
            throw new AssertionError("intake must not prepare or dispatch a production phase");
        }
    };

    AgentPackProductionIntakeService intake() {
        var orders = unused(WorkOrderRepository.class);
        var candidates = unused(CandidateVersionRepository.class);
        var protocol = new WorkerProtocolArtifacts(artifacts, unused(WorkerProtocolArtifactDecoder.class));
        var profiles = new AgentPackGenerationVerificationProfiles(orders, protocol, candidates);
        var verifications = unused(VerificationRunRepository.class);
        var owners = unused(VerificationActionEvidenceOwner.class);
        var reviews = new AgentPackReleaseReviewService(sessions, candidates, verifications, profiles,
                unused(VerificationEvidenceValidator.class), owners, artifacts, unused(AgentPackReleaseReviewTransaction.class),
                clock, Duration.ofMinutes(10));
        var findings = new AgentPackProductionRepairFindings(sessions, verifications, profiles, owners,
                unused(DecisionPointRepository.class), unused(DecisionRepository.class),
                unused(AgentPackProductionRepairEvidenceReader.class), clock);
        AgentPackProductionCodec codec = new AgentPackProductionCodec() {
            public byte[] writePlan(AgentPackProductionPlan plan) { return bytes(plan.toString()); }
            public AgentPackProductionPlan readPlan(byte[] bytes) { throw new AssertionError("intake must not prepare work"); }
            public byte[] writeWorkerInput(CodingWorkerInputManifest input) { throw new AssertionError("no Worker input during intake"); }
            public byte[] normalizeBlueprint(byte[] bytes) { throw new AssertionError("no blueprint during intake"); }
            public List<String> blueprintSourceFiles(byte[] bytes) { throw new AssertionError("no blueprint during intake"); }
        };
        var service = new AgentPackProductionService(sessions, orders, candidates, artifacts, protocol, codec, transaction,
                reviews, findings, clock, Duration.ofMinutes(10));
        return new AgentPackProductionIntakeService(service, (tenant, id) -> {
            inputReads.incrementAndGet(); duringInputs.run();
            if (inputFailure != null) throw inputFailure;
            return alternateBundle == null ? bundle(tenant, id) : alternateBundle;
        }, clock);
    }

    static AgentPackProductionIntakeInput input(String id) {
        return new AgentPackProductionIntakeInput(new BuildSessionId(id), new ProjectId("project-" + id),
                NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(600), 2);
    }
    static AgentPackProductionIntakeInput input(BuildSession session) {
        return new AgentPackProductionIntakeInput(session.buildSessionId(), session.projectId(), session.deadlineAt(), session.maxRepairRounds());
    }

    static AgentPackProductionIntakeInputs.Bundle bundle(TenantId tenant, BuildSessionId session) {
        var staged = new ArrayList<>(MaintenanceInvestigationProductContract.artifacts(tenant));
        for (String name : List.of("skill", "dependency", "toolchain", "policy")) {
            staged.add(new Artifact(tenant, ref(name), hash(bytes(name)), "text/plain", bytes(name)));
        }
        var capabilities = new WorkerCapabilities(Set.of(WorkerCapabilityCatalog.REPOSITORY_READ,
                WorkerCapabilityCatalog.BOUNDED_PATCH_WRITE, WorkerCapabilityCatalog.FILE_CREATE,
                WorkerCapabilityCatalog.STRUCTURED_OUTPUT, WorkerCapabilityCatalog.COOPERATIVE_CANCEL,
                WorkerCapabilityCatalog.SANDBOX_ENFORCEMENT));
        var plan = new AgentPackProductionPlan(AgentPackProductionPlan.SCHEMA_VERSION, tenant, session,
                MaintenanceProductionRecipe.ID, MaintenanceInvestigationProductContract.requirementsLock(),
                "flower", "0.3.3", lock("skill"), lock("dependency"), lock("toolchain"),
                MaintenanceInvestigationProductContract.lock(), MaintenanceInvestigationProductContract.apiSignatureIndexLock(),
                MaintenanceInvestigationProductContract.requirementTestMatrixLock(), MaintenanceInvestigationProductContract.GATE_PROFILE,
                lock("policy"), "workspace-" + session.value(),
                new AgentPackProductionPlan.WorkerBinding("manager", "1", capabilities),
                new AgentPackProductionPlan.WorkerBinding("coding", "1", capabilities));
        return new AgentPackProductionIntakeInputs.Bundle(plan, staged);
    }

    private static List<String> locks(List<Artifact> artifacts) {
        return artifacts.stream().map(a -> a.reference().value() + ":" + a.contentHash().sha256()).sorted().toList();
    }
    private static ArtifactReference ref(String name) { return new ArtifactReference("artifact:intake-test:" + name); }
    private static CertificationArtifactLock lock(String name) { return new CertificationArtifactLock(ref(name), hash(bytes(name))); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static ContentHash hash(byte[] value) {
        try { return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static <T> T unused(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, args) -> {
            throw new AssertionError("intake called out-of-scope port " + type.getSimpleName() + "." + method.getName());
        }));
    }

    static final class Sessions implements BuildSessionRepository {
        private final ConcurrentHashMap<String, BuildSession> rows = new ConcurrentHashMap<>();
        private static String key(TenantId tenant, BuildSessionId id) { return tenant.value() + "\n" + id.value(); }
        public void create(BuildSession session) {
            if (rows.putIfAbsent(key(session.tenantId(), session.buildSessionId()), session) != null) throw new IllegalArgumentException("duplicate");
        }
        public Optional<BuildSession> find(TenantId tenant, BuildSessionId id) { return Optional.ofNullable(rows.get(key(tenant, id))); }
        public boolean compareAndSet(BuildSession expected, BuildSession next) {
            if (next.version() != expected.version() + 1) throw new IllegalArgumentException("invalid version");
            return rows.replace(key(expected.tenantId(), expected.buildSessionId()), expected, next);
        }
    }
    static final class MutableClock extends Clock {
        volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
    }
    @SuppressWarnings("unchecked")
    static <T extends Record> T copy(T source, Object... replacements) {
        try {
            var components = source.getClass().getRecordComponents();
            var types = new Class<?>[components.length]; var values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType(); values[i] = components[i].getAccessor().invoke(source);
                for (int j = 0; j < replacements.length; j += 2) {
                    if (components[i].getName().equals(replacements[j])) values[i] = replacements[j + 1];
                }
            }
            return (T) source.getClass().getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
