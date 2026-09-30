package io.github.flowerjvm.factory.application.referenceassembly;

import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.build.*;
import io.github.flowerjvm.factory.application.candidate.*;
import io.github.flowerjvm.factory.application.certification.*;
import io.github.flowerjvm.factory.application.verification.*;
import io.github.flowerjvm.factory.contracts.artifact.*;
import io.github.flowerjvm.factory.contracts.certification.*;
import io.github.flowerjvm.factory.contracts.ids.*;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import io.github.flowerjvm.factory.contracts.verification.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Real intake controls and service, synthetic certification/read gate and atomic in-memory store. Not production evidence. */
final class ReferenceAssemblyIntakeTestFixture {
    static final Instant NOW = Instant.parse("2026-09-07T00:00:00.123456Z");
    static final TenantId TENANT = new TenantId("ra-intake-tenant");
    final MutableClock clock = new MutableClock();
    final ResolvedCertifiedAgentComponent resolved = component();
    volatile Certification certification = resolved.certification();
    final Map<String, BuildSession> sessionRows = new ConcurrentHashMap<>();
    final Map<String, Artifact> artifactRows = new ConcurrentHashMap<>();
    final AtomicInteger fullReads = new AtomicInteger();
    final AtomicInteger commits = new AtomicInteger();
    final AtomicInteger attempts = new AtomicInteger();
    Runnable duringFullRead = () -> {};
    Runnable beforeCommit = () -> {};
    boolean failAfterCommit;

    final BuildSessionRepository sessions = new BuildSessionRepository() {
        public void create(BuildSession value) { if (sessionRows.putIfAbsent(key(value.tenantId(), value.buildSessionId().value()), value) != null) throw new IllegalArgumentException("duplicate"); }
        public Optional<BuildSession> find(TenantId tenant, BuildSessionId id) { return Optional.ofNullable(sessionRows.get(key(tenant, id.value()))); }
        public boolean compareAndSet(BuildSession expected, BuildSession next) { return sessionRows.replace(key(expected.tenantId(), expected.buildSessionId().value()), expected, next); }
    };
    final ArtifactStore artifacts = new ArtifactStore() {
        public ArtifactReference store(Artifact value) { throw new AssertionError("only the intake transaction may store artifacts"); }
        public Optional<Artifact> find(TenantId tenant, ArtifactReference ref) { return Optional.ofNullable(artifactRows.get(key(tenant, ref.value()))); }
    };
    final CertificationRepository certifications = new CertificationRepository() {
        public void create(Certification value) { throw new AssertionError("intake cannot certify a component"); }
        public Optional<Certification> find(TenantId tenant, CertificationId id) {
            return certification != null && certification.inputLock().tenantId().equals(tenant) && certification.certificationId().equals(id)
                    ? Optional.of(certification) : Optional.empty();
        }
        public boolean compareAndSet(Certification expected, Certification next) { throw new AssertionError("intake cannot change certified source"); }
    };
    final CertifiedAgentComponentReadGate fullGate = (tenant, ref) -> {
        fullReads.incrementAndGet(); duringFullRead.run();
        if (!TENANT.equals(tenant) || !resolved.reference().equals(ref) || !resolved.certification().equals(certification)) {
            throw new IllegalArgumentException("full certification gate refused the component");
        }
        return resolved;
    };
    final ReferenceAssemblyArtifactCodec codec = codec();
    final ReferenceAssemblyIntakeTransaction transaction = (pristine, component, staged) -> {
        attempts.incrementAndGet(); beforeCommit.run();
        synchronized (artifactRows) {
            if (!resolved.certification().equals(certification)) throw new IllegalArgumentException("locked certification changed");
            input(pristine.buildSessionId().value()).requireLiveAt(clock.instant());
            Artifact receipt = ReferenceAssemblyIntakeReceipt.artifactFor(pristine, component);
            Artifact old = artifactRows.get(key(pristine.tenantId(), receipt.reference().value()));
            var existing = sessions.find(pristine.tenantId(), pristine.buildSessionId());
            if (old != null) {
                if (!ReferenceAssemblyIntakeReceipt.exact(old, receipt) || existing.isEmpty()) throw new IllegalArgumentException("immutable key conflict");
                ReferenceAssemblyIntakeService.requireExact(existing.orElseThrow(), pristine.tenantId(), pristine.createdBy(),
                        pristine.requestIdempotencyKey(), fromSession(pristine, component),
                        new CertificationArtifactLock(pristine.requirementsArtifactRef(), pristine.requirementsHash()));
                return existing.orElseThrow();
            }
            if (existing.isPresent()) throw new IllegalArgumentException("unreceipted legacy session");
            staged.forEach(a -> artifactRows.put(key(a.tenantId(), a.reference().value()), a));
            sessions.create(pristine); commits.incrementAndGet();
            if (failAfterCommit) throw new IllegalStateException("simulated lost acknowledgement");
            return pristine;
        }
    };

    ReferenceAssemblyIntakeService service() { return new ReferenceAssemblyIntakeService(sessions, certifications, fullGate, artifacts, codec, transaction, clock); }
    ReferenceAssemblyIntakeInput input(String name) {
        return new ReferenceAssemblyIntakeInput(new BuildSessionId(name), new ProjectId("project-" + name), NOW.plusSeconds(600),
                ReferenceAssemblyProductLineCatalog.Entry.MAINTENANCE_INVESTIGATION_V1,
                resolved.reference().certificationId(), resolved.reference().candidateHash(), resolved.reference().certificationManifest());
    }
    static ReferenceAssemblyIntakeInput fromSession(BuildSession session, CertifiedAgentComponentRef component) {
        return new ReferenceAssemblyIntakeInput(session.buildSessionId(), session.projectId(), session.deadlineAt(),
                ReferenceAssemblyProductLineCatalog.Entry.MAINTENANCE_INVESTIGATION_V1, component.certificationId(),
                component.candidateHash(), component.certificationManifest());
    }
    static String key(TenantId tenant, String id) { return tenant.value() + "\n" + id; }

    private static ResolvedCertifiedAgentComponent component() {
        var build = new BuildSessionId("source-build"); var order = new WorkOrderId("source-order");
        var candidateId = new CandidateId("source-candidate"); var verificationId = new VerificationRunId("source-verification");
        var certificationId = new CertificationId("source-certification"); var source = lock("source");
        var dependency = lock("dependency"); var toolchain = lock("toolchain"); var generation = lock("generation");
        var result = lock("verification-result"); var compatibilityLock = lock("compatibility");
        var inputArtifact = lock("input-lock"); var evidenceLock = lock("evidence"); var manifestLock = lock("manifest");
        var product = MaintenanceInvestigationProductContract.lock(); var api = MaintenanceInvestigationProductContract.apiSignatureIndexLock();
        String gate = MaintenanceInvestigationProductContract.GATE_PROFILE; String algorithm = CandidateSourceManifest.SOURCE_LOCK_ALGORITHM_ID;
        var input = new CertificationInputLock(CertificationInputLock.SCHEMA_VERSION, TENANT, ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK, build, order, candidateId, source.hash(), source, dependency, toolchain,
                generation, product, api, algorithm, gate, verificationId, "verification-action", result, lock("fixture").hash(),
                lock("policy"), compatibilityLock, "internal", "0.2.0", "0.1.3", "0.3.3");
        var certification = Certification.requested(certificationId, input, inputArtifact, NOW)
                .certify(manifestLock, evidenceLock, "certification-action", NOW, Optional.of(NOW.plusSeconds(3600)));
        var ref = new CertifiedAgentComponentRef(CertifiedAgentComponentRef.SCHEMA_VERSION, ReferenceAssemblyRequirement.COMPONENT_ROLE,
                ProductLineId.AGENT_PACK, CertifiedArtifactType.AGENT_PACK, certificationId, manifestLock, candidateId,
                source.hash(), source, inputArtifact, verificationId, result, compatibilityLock, evidenceLock, "internal");
        var compatibility = new AgentPackCompatibilityDescriptor(AgentPackCompatibilityDescriptor.SCHEMA_VERSION, TENANT,
                ProductLineId.AGENT_PACK, CertifiedArtifactType.AGENT_PACK, candidateId, source.hash(), product, api, dependency,
                toolchain, gate, algorithm, "0.2.0", "0.1.3", "0.3.3");
        var evidence = new CertificationEvidenceManifest(CertificationEvidenceManifest.SCHEMA_VERSION, TENANT, ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK, certificationId, candidateId, source.hash(), inputArtifact.hash(), verificationId,
                "verification-action", result.hash(), compatibilityLock.hash(), "internal", "0.2.0", "0.1.3", "0.3.3");
        var manifest = new CertifiedAgentComponentManifest(CertifiedAgentComponentManifest.SCHEMA_VERSION, TENANT, ProductLineId.AGENT_PACK,
                CertifiedArtifactType.AGENT_PACK, certificationId, candidateId, source.hash(), source, inputArtifact, verificationId,
                result, compatibilityLock, evidenceLock, "internal", "0.2.0", NOW, NOW.plusSeconds(3600), CertifiedAgentComponentManifest.CERTIFIED_STATUS);
        var candidate = new CandidateVersion(candidateId, TENANT, build, Optional.empty(), source.reference(), source.hash(),
                dependency.reference(), dependency.hash(), toolchain.reference(), toolchain.hash(), CandidateVersionStatus.GENERATED, order, NOW);
        var verification = new VerificationRun(verificationId, TENANT, build, candidateId, source.hash(), gate, toolchain.hash(),
                lock("fixture").hash(), VerificationRunStatus.PASSED, Optional.of(result.reference()), Optional.of(result.hash()),
                Optional.of("VERIFICATION_PASSED"), Optional.of(VerificationDisposition.REVIEW_ELIGIBLE), Optional.of(NOW), Optional.of(NOW), 2, NOW, NOW);
        return new ResolvedCertifiedAgentComponent(ref, certification, input, evidence, manifest, compatibility, candidate, verification);
    }

    static CertificationArtifactLock lock(String name) {
        return new CertificationArtifactLock(new ArtifactReference("artifact:ra-intake-test:" + name),
                ReferenceAssemblyIntakeReceipt.hash(name.getBytes(StandardCharsets.UTF_8)));
    }

    private static ReferenceAssemblyArtifactCodec codec() {
        var values = new ConcurrentHashMap<String, Object>();
        return (ReferenceAssemblyArtifactCodec) Proxy.newProxyInstance(ReferenceAssemblyArtifactCodec.class.getClassLoader(),
                new Class<?>[]{ReferenceAssemblyArtifactCodec.class}, (instance, method, args) -> {
                    if (method.getName().startsWith("write")) {
                        String text = args[0].toString(); values.put(text, args[0]); return text.getBytes(StandardCharsets.UTF_8);
                    }
                    if (method.getName().startsWith("read")) return Objects.requireNonNull(values.get(new String((byte[]) args[0], StandardCharsets.UTF_8)));
                    throw new AssertionError("unexpected codec call");
                });
    }

    static final class MutableClock extends Clock {
        volatile Instant now = NOW;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
    }
    @SuppressWarnings("unchecked") static <T extends Record> T copy(T original, Object... updates) {
        try {
            var components = original.getClass().getRecordComponents(); var types = new Class<?>[components.length]; var values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType(); values[i] = components[i].getAccessor().invoke(original);
                for (int j = 0; j < updates.length; j += 2) if (components[i].getName().equals(updates[j])) values[i] = updates[j + 1];
            }
            return (T) original.getClass().getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
