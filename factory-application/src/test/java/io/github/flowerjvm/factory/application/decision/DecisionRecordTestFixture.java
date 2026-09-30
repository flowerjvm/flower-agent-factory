package io.github.flowerjvm.factory.application.decision;

import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.referenceassembly.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.flower.action.runtime.*;
import io.github.flowerjvm.flower.action.runtime.action.*;
import io.github.flowerjvm.flower.action.runtime.approval.ApprovalGate;
import io.github.flowerjvm.flower.action.runtime.audit.*;
import io.github.flowerjvm.flower.action.runtime.duplicate.*;
import io.github.flowerjvm.flower.action.runtime.guard.PreExecutionGuard;
import io.github.flowerjvm.flower.action.runtime.policy.PolicyGate;
import io.github.flowerjvm.flower.action.runtime.run.InMemoryRunStore;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Real services and runtime, with an atomic in-memory transaction double; never a live database or approval. */
final class DecisionRecordTestFixture {
    static final TenantId TENANT = new TenantId("decision-tenant");
    static final Instant NOW = Instant.parse("2026-09-06T14:00:00.123456Z");
    static final ContentHash HASH = new ContentHash("a".repeat(64));
    static final ArtifactReference AUTH = new ArtifactReference("artifact:host-operator-authority");
    final Map<BuildSessionId, BuildSession> sessionRows = new ConcurrentHashMap<>();
    final Map<DecisionPointId, DecisionPoint> pointRows = new ConcurrentHashMap<>();
    final Map<DecisionId, Decision> decisionRows = new ConcurrentHashMap<>();
    final Map<ReferenceAssemblyId, ReferenceAssembly> assemblyRows = new ConcurrentHashMap<>();
    final MutableClock clock = new MutableClock();
    final AtomicInteger commits = new AtomicInteger();
    final AtomicInteger executions = new AtomicInteger();
    final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
    final InMemoryRunStore runs = new InMemoryRunStore();
    Runnable beforeCommit = () -> {};
    Runnable beforeServiceSessionRead = () -> {};
    boolean failAfterCommit;
    final BuildSessionRepository sessions = new BuildSessionRepository() {
        public void create(BuildSession value) { sessionRows.put(value.buildSessionId(), value); }
        public Optional<BuildSession> find(TenantId tenant, BuildSessionId id) {
            return Optional.ofNullable(sessionRows.get(id)).filter(value -> value.tenantId().equals(tenant));
        }
        public boolean compareAndSet(BuildSession expected, BuildSession next) { return sessionRows.replace(expected.buildSessionId(), expected, next); }
    };
    final DecisionPointRepository points = new DecisionPointRepository() {
        public void create(DecisionPoint value) { pointRows.put(value.decisionPointId(), value); }
        public Optional<DecisionPoint> find(TenantId tenant, DecisionPointId id) {
            return Optional.ofNullable(pointRows.get(id)).filter(value -> value.tenantId().equals(tenant));
        }
        public boolean compareAndSet(DecisionPoint expected, DecisionPoint next) { return pointRows.replace(expected.decisionPointId(), expected, next); }
    };
    final DecisionRepository decisions = new DecisionRepository() {
        public void create(Decision value) { decisionRows.put(value.decisionId(), value); }
        public Optional<Decision> find(TenantId tenant, DecisionId id) {
            return Optional.ofNullable(decisionRows.get(id)).filter(value -> value.tenantId().equals(tenant));
        }
        public Optional<Decision> findByRequestIdempotencyKey(TenantId tenant, DecisionPointId id, String key) {
            return decisionRows.values().stream().filter(value -> value.tenantId().equals(tenant)
                    && value.decisionPointId().equals(id) && value.requestIdempotencyKey().equals(key)).findFirst();
        }
    };
    @SuppressWarnings("unchecked")
    final ReferenceAssemblyRepository assemblies = (ReferenceAssemblyRepository) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {ReferenceAssemblyRepository.class}, (proxy, method, args) -> {
                if (method.getName().equals("find")) return Optional.ofNullable(assemblyRows.get(args[1]))
                        .filter(value -> value.tenantId().equals(args[0]));
                throw new AssertionError("unexpected reference assembly mutation");
            });
    final List<DecisionSubjectAuthority> subjectAuthorities = List.of(new AgentPackReleaseReviewDecisionSubjectAuthority(),
            new ReferenceAssemblyReleaseDecisionSubjectAuthority(assemblies));
    DecisionRecordAdmission admission() { return new DecisionRecordAdmission(sessions, points, decisions, subjectAuthorities, clock); }
    DecisionRecordingService service() {
        BuildSessionRepository serviceSessions = new BuildSessionRepository() {
            public void create(BuildSession value) { throw new AssertionError(); }
            public boolean compareAndSet(BuildSession a, BuildSession b) { throw new AssertionError(); }
            public Optional<BuildSession> find(TenantId tenant, BuildSessionId id) { beforeServiceSessionRead.run(); return sessions.find(tenant, id); }
        };
        return new DecisionRecordingService(serviceSessions, points, decisions, (session, point, decision, decided) -> {
            beforeCommit.run();
            synchronized (decisionRows) {
                if (!session.equals(sessionRows.get(session.buildSessionId())) || !point.equals(pointRows.get(point.decisionPointId()))) {
                    return decisions.findByRequestIdempotencyKey(decision.tenantId(), decision.decisionPointId(), decision.requestIdempotencyKey())
                            .filter(decision::hasSameRequestPayload).isPresent() ? DecisionRecordingDisposition.DUPLICATE : DecisionRecordingDisposition.CONFLICT;
                }
                decisionRows.put(decision.decisionId(), decision); pointRows.put(point.decisionPointId(), decided); commits.incrementAndGet();
            }
            if (failAfterCommit) throw new IllegalStateException("private-driver-failure");
            return DecisionRecordingDisposition.APPLIED;
        }, subjectAuthorities);
    }
    DefaultActionRuntime runtime() { return runtime(new DecisionRecordPolicyGate(admission()),
            new InMemoryDuplicateActionPolicy(new DecisionRecordVisibilityScopeResolver(admission())), new DecisionRecordPreExecutionGuard(admission())); }
    DefaultActionRuntime runtime(PolicyGate policy, DuplicateActionPolicy duplicate, PreExecutionGuard guard) {
        var delegate = new DecisionRecordActionExecutor(service(), admission());
        SynchronousActionExecutor counted = new SynchronousActionExecutor() {
            public ActionDefinition definition() { return delegate.definition(); }
            public ActionExecutionResult execute(ActionExecutionContext context) { executions.incrementAndGet(); return delegate.execute(context); }
        };
        return new DefaultActionRuntime(new InMemoryActionRegistry(List.of(counted)), new DecisionRecordActionValidator(), policy,
                ApprovalGate.unsupported(), duplicate, audit::add, TraceSink.noop(), runs, guard);
    }
    DecisionPoint add(String suffix, boolean assembly) {
        var sid = new BuildSessionId("session-" + suffix); var candidate = new CandidateId("candidate-" + suffix);
        var session = new BuildSession(sid, TENANT, new ProjectId("project-" + suffix), assembly ? ProductLineId.REFERENCE_ASSEMBLY : ProductLineId.AGENT_PACK,
                "order-" + suffix, "factory-builder", BuildSessionStatus.WAITING_RELEASE_REVIEW, BuildSessionPhase.HUMAN_RELEASE_REVIEW,
                ref("requirements-" + suffix), HASH, Optional.empty(), Optional.empty(), Optional.empty(),
                assembly ? Optional.empty() : Optional.of(candidate), assembly ? Optional.empty() : Optional.of(HASH), Optional.empty(),
                0, 3, NOW.minusSeconds(10), NOW.plusSeconds(600), Optional.empty(), Optional.empty(), Optional.empty(), 4,
                NOW.minusSeconds(10), NOW.minusSeconds(1));
        sessions.create(session);
        var aid = new ReferenceAssemblyId("assembly-" + suffix);
        var point = new DecisionPoint(new DecisionPointId("point-" + suffix), TENANT, sid,
                assembly ? ReferenceAssemblyReleaseReviewService.DECISION_TYPE : DecisionPoint.RELEASE_REVIEW_TYPE, DecisionPointStatus.OPEN,
                assembly ? ReferenceAssemblyReleaseReviewService.SUBJECT_TYPE : AgentPackReleaseReviewDecisionSubjectAuthority.SUBJECT_TYPE,
                assembly ? aid.value() : candidate.value(), assembly ? 2 : 0, HASH, ref("question-" + suffix),
                assembly ? ReferenceAssemblyReleaseReviewService.OPTIONS_SCHEMA_ID : AgentPackReleaseReviewPolicy.OPTIONS_SCHEMA_ID,
                Set.of(assembly ? ReferenceAssemblyReleaseReviewService.REQUIRED_PERMISSION : AgentPackReleaseReviewPolicy.REQUIRED_PERMISSION),
                1, ref("policy-" + suffix), NOW.minusSeconds(1), session.deadlineAt(), Optional.empty(), Optional.empty(), 0);
        points.create(point);
        if (assembly) assemblyRows.put(aid, new ReferenceAssembly(aid, TENANT, sid, new CertificationArtifactLock(session.requirementsArtifactRef(), HASH),
                lock("consumer"), lock("fixture"), lock("policy-" + suffix), new CertificationId("cert-" + suffix), HASH, lock("component"),
                ReferenceAssemblyStatus.INSPECTED, Optional.of(lock("assembly")), Optional.of(lock("inspection")), Optional.empty(),
                Optional.of(point.decisionPointId()), Optional.of(HASH), Optional.empty(), Optional.empty(), 3, NOW.minusSeconds(5), NOW.minusSeconds(1)));
        return point;
    }
    DecisionRecordAuthority authority(DecisionPoint point) {
        var permissions = new HashSet<>(point.requiredPermissions()); permissions.add(DecisionRecordAction.PERMISSION);
        return new DecisionRecordAuthority(TENANT, sessionRows.get(point.buildSessionId()).projectId(), "operator-a", permissions, AUTH);
    }
    static DecisionRecordInput input(DecisionPoint point) { return new DecisionRecordInput(point.decisionPointId(), 0, point.subjectHash(), DecisionOutcome.APPROVE, Optional.empty()); }
    static ActionProposal proposal(DecisionRecordInput input, String key, String principal) {
        return ActionProposal.builder(DecisionRecordAction.ACTION_ID).proposalId(UUID.randomUUID().toString())
                .requestChannel(ActionRequestChannel.CLI).proposerType(ActionProposerType.USER).requesterId(principal)
                .reason("synthetic test operator decision").input(input.toMap()).idempotencyKey(key).build();
    }
    static ExecutionContext context(DecisionRecordAuthority authority, DecisionPoint point) {
        return new ExecutionContext(authority.tenantId().value(), authority.principal(), UUID.randomUUID().toString(), "test-trace",
                Map.of("actor.permissions", authority.permissions(), "actor.authoritySnapshotRef", authority.authoritySnapshotRef().value(),
                        "resource.type", DecisionRecordAction.RESOURCE_TYPE, "resource.id", point.decisionPointId().value(), "resource.projectId", authority.projectId().value()));
    }
    static ArtifactReference ref(String value) { return new ArtifactReference("artifact:" + value); }
    static CertificationArtifactLock lock(String value) { return new CertificationArtifactLock(ref(value), HASH); }
    @SuppressWarnings("unchecked")
    static <T> T copy(T record, String field, Object value) {
        try {
            var components = record.getClass().getRecordComponents(); Class<?>[] types = new Class<?>[components.length]; Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) { types[i] = components[i].getType(); values[i] = components[i].getName().equals(field) ? value : components[i].getAccessor().invoke(record); }
            return (T) record.getClass().getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    final class MutableClock extends Clock {
        Instant instant = NOW;
        public Instant instant() { return instant; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
}
