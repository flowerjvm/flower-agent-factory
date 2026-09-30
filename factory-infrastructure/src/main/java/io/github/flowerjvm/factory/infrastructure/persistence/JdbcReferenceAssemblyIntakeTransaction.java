package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.factory.application.acceptance.maintenance.MaintenanceInvestigationProductContract;
import io.github.flowerjvm.factory.application.build.BuildSession;
import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.build.BuildSessionStatus;
import io.github.flowerjvm.factory.application.certification.Certification;
import io.github.flowerjvm.factory.application.certification.CertificationStatus;
import io.github.flowerjvm.factory.application.certification.CertifiedAgentComponentReadGate;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyAssembler;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyIntakeInput;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyIntakeAction;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyIntakeReceipt;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyIntakeTransaction;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyProductLineCatalog;
import io.github.flowerjvm.factory.contracts.artifact.Artifact;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactStore;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertifiedAgentComponentRef;
import io.github.flowerjvm.factory.contracts.ids.ProductLineId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.infrastructure.referenceassembly.JacksonReferenceAssemblyArtifactCodec;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Atomic, registered-Action intake only. Lock order is immutable request receipt, BuildSession,
 * then component Certification. The full component gate runs while the certification row is
 * locked; no certification, assembly, inspection, approval or release is manufactured here.
 */
public final class JdbcReferenceAssemblyIntakeTransaction implements ReferenceAssemblyIntakeTransaction {
    private static final ReferenceAssemblyProductLineCatalog.Entry ENTRY =
            ReferenceAssemblyProductLineCatalog.Entry.MAINTENANCE_INVESTIGATION_V1;
    private final DataSource dataSource;
    private final JdbcBuildSessionRepository sessions;
    private final JdbcCertificationRepository certifications;
    private final JdbcArtifactStore artifacts;
    private final JacksonReferenceAssemblyArtifactCodec codec;
    private final CertifiedAgentComponentReadGate componentReadGate;
    private final Clock clock;

    public JdbcReferenceAssemblyIntakeTransaction(DataSource dataSource, ObjectMapper mapper,
                                                 CertifiedAgentComponentReadGate componentReadGate, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.componentReadGate = Objects.requireNonNull(componentReadGate, "componentReadGate");
        this.clock = Objects.requireNonNull(clock, "clock");
        sessions = new JdbcBuildSessionRepository(dataSource);
        certifications = new JdbcCertificationRepository(dataSource);
        artifacts = new JdbcArtifactStore(dataSource, clock);
        codec = new JacksonReferenceAssemblyArtifactCodec(Objects.requireNonNull(mapper, "mapper"));
    }

    @Override
    public BuildSession accept(BuildSession requested, CertifiedAgentComponentRef component,
                               List<Artifact> stagedArtifacts) {
        requirePristine(Objects.requireNonNull(requested, "pristineSession"));
        Objects.requireNonNull(component, "component");
        var input = new ReferenceAssemblyIntakeInput(requested.buildSessionId(), requested.projectId(),
                requested.deadlineAt(), ENTRY, component.certificationId(), component.candidateHash(),
                component.certificationManifest());
        var scratch = new StagingStore();
        var catalog = new ReferenceAssemblyProductLineCatalog(scratch, codec);
        var requirement = catalog.stageRequirement(requested.tenantId(), component, ENTRY);
        require(requirement.equals(new CertificationArtifactLock(
                requested.requirementsArtifactRef(), requested.requirementsHash())), "requirement is not the exact catalog graph");
        Artifact receipt = ReferenceAssemblyIntakeReceipt.artifactFor(requested, component);
        scratch.store(receipt);
        List<Artifact> expectedArtifacts = scratch.snapshot();
        requireExactStaging(expectedArtifacts, stagedArtifacts);

        return withConnection(dataSource, "accept Reference Assembly request", connection -> {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                // Immutable insert, not an upsert: concurrent changed payloads cannot share a key.
                boolean insertedReceipt = artifacts.storeIfAbsent(connection, receipt);
                var existing = sessions.find(connection, requested.tenantId(), requested.buildSessionId(), true);
                require(insertedReceipt == existing.isEmpty(),
                        "intake receipt and session have inconsistent ownership; no retroactive repair is allowed");
                if (existing.isPresent()) {
                    require(sameAcceptance(existing.orElseThrow(), requested), "intake conflicts with existing session");
                    require(existing.orElseThrow().cancellationRequestedAt().isEmpty()
                                    && existing.orElseThrow().status() != BuildSessionStatus.CANCELLED,
                            "cancelled intake cannot be resumed");
                    requireStoredArtifacts(connection, expectedArtifacts);
                }
                Certification locked = certifications.find(connection, requested.tenantId(),
                                component.certificationId(), true)
                        .orElseThrow(() -> invalid("component certification is missing in the trusted tenant"));
                requireCurrent(locked, clock.instant());
                // Reuse the existing product compatibility checks and the real full read gate.
                // Only code-owned staged inputs are in scratch; component evidence stays canonical.
                var resolved = new ReferenceAssemblyAssembler(scratch, codec, componentReadGate,
                        catalog.admissionPolicy(ENTRY)).resolveComponent(requested.tenantId(), requirement).component();
                require(resolved.certification().equals(locked) && resolved.inputLock().equals(locked.inputLock())
                                && resolved.reference().equals(component),
                        "component gate did not resolve the exact locked certification snapshot");
                require(MaintenanceInvestigationProductContract.GATE_PROFILE.equals(locked.inputLock().gateProfile()),
                        "component certification is not the Maintenance Investigation gate");
                Instant now = clock.instant();
                requireCurrent(locked, now);
                input.requireLiveAt(now);
                require(!now.isBefore(requested.createdAt()), "intake timestamp is in the future");
                require(existing.map(value -> !now.isBefore(value.updatedAt())).orElse(true),
                        "intake clock moved behind its canonical session");
                if (existing.isEmpty()) {
                    for (Artifact artifact : expectedArtifacts) artifacts.store(connection, artifact);
                    sessions.create(connection, requested);
                }
                // JDBC staging also consumes time. Expiry while writing must roll back the entire
                // intake, not leave a newly accepted session behind an already closed boundary.
                Instant committedAt = clock.instant();
                requireCurrent(locked, committedAt);
                input.requireLiveAt(committedAt);
                require(!committedAt.isBefore(now), "intake clock moved backwards before commit");
                connection.commit();
                return existing.orElse(requested);
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        });
    }

    private static void requirePristine(BuildSession session) {
        require(session.productLineId().equals(ProductLineId.REFERENCE_ASSEMBLY)
                        && session.createdBy().equals(ReferenceAssemblyIntakeAction.REQUESTER_ID)
                        && session.status() == BuildSessionStatus.RUNNING
                        && session.currentPhase() == BuildSessionPhase.UNDERSTAND_CUSTOMER
                        && session.version() == 0 && session.repairRound() == 0 && session.maxRepairRounds() == 0
                        && session.selectedManagerWorkerBinding().isEmpty() && session.selectedCodingWorkerBinding().isEmpty()
                        && session.currentBlueprintRef().isEmpty() && session.currentCandidateId().isEmpty()
                        && session.currentCandidateHash().isEmpty() && session.currentCertificationId().isEmpty()
                        && session.cancellationRequestedAt().isEmpty() && session.terminalCode().isEmpty()
                        && session.terminalMessage().isEmpty() && session.startedAt().equals(session.createdAt())
                        && session.updatedAt().equals(session.createdAt())
                        && session.createdAt().equals(session.createdAt().truncatedTo(ChronoUnit.MICROS)),
                "intake requires a pristine microsecond-precise Reference Assembly session");
    }

    private static void requireCurrent(Certification certification, Instant now) {
        require(certification.status() == CertificationStatus.CERTIFIED && certification.revokedAt().isEmpty()
                        && !now.isBefore(certification.updatedAt())
                        && certification.expiresAt().map(now::isBefore).orElse(true),
                "component certification is not currently eligible");
    }

    private static boolean sameAcceptance(BuildSession left, BuildSession right) {
        return left.buildSessionId().equals(right.buildSessionId()) && left.tenantId().equals(right.tenantId())
                && left.projectId().equals(right.projectId()) && left.productLineId().equals(right.productLineId())
                && left.requestIdempotencyKey().equals(right.requestIdempotencyKey()) && left.createdBy().equals(right.createdBy())
                && left.requirementsArtifactRef().equals(right.requirementsArtifactRef())
                && left.requirementsHash().equals(right.requirementsHash())
                && left.selectedManagerWorkerBinding().equals(right.selectedManagerWorkerBinding())
                && left.selectedCodingWorkerBinding().equals(right.selectedCodingWorkerBinding())
                && left.maxRepairRounds() == right.maxRepairRounds() && left.deadlineAt().equals(right.deadlineAt());
    }

    private static void requireExactStaging(List<Artifact> expected, List<Artifact> requested) {
        require(requested != null && requested.size() == expected.size(), "staged catalog artifact set differs");
        var byRef = new LinkedHashMap<ArtifactReference, Artifact>();
        for (Artifact artifact : requested) {
            require(artifact != null && artifact.content().length <= 1024 * 1024, "staged artifact exceeds its bound");
            require(byRef.putIfAbsent(artifact.reference(), artifact) == null, "duplicate staged artifact reference");
        }
        for (Artifact artifact : expected) {
            require(exact(byRef.get(artifact.reference()), artifact), "staged artifact is not exact code-owned content");
        }
    }

    private void requireStoredArtifacts(Connection connection, List<Artifact> expected) throws SQLException {
        for (Artifact artifact : expected) {
            require(exact(artifacts.find(connection, artifact.tenantId(), artifact.reference()).orElse(null), artifact),
                    "retry is missing an exact committed catalog artifact");
        }
    }

    private static boolean exact(Artifact actual, Artifact expected) {
        return actual != null && actual.tenantId().equals(expected.tenantId())
                && actual.reference().equals(expected.reference()) && actual.contentHash().equals(expected.contentHash())
                && actual.mediaType().equals(expected.mediaType()) && Arrays.equals(actual.content(), expected.content());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw invalid(message);
    }

    private static FactoryPersistenceException invalid(String message) {
        return new FactoryPersistenceException(message, null);
    }

    /** Temporary, bounded code-owned catalog projection; never publishes outside this transaction. */
    private static final class StagingStore implements ArtifactStore {
        private final Map<ArtifactReference, Artifact> values = new LinkedHashMap<>();

        @Override public ArtifactReference store(Artifact artifact) {
            Artifact existing = values.putIfAbsent(artifact.reference(), artifact);
            require(existing == null || exact(existing, artifact), "temporary catalog conflict");
            return artifact.reference();
        }

        @Override public Optional<Artifact> find(TenantId tenant, ArtifactReference reference) {
            return Optional.ofNullable(values.get(reference)).filter(value -> value.tenantId().equals(tenant));
        }

        List<Artifact> snapshot() {
            return values.values().stream().sorted(Comparator.comparing(value -> value.reference().value())).toList();
        }
    }
}
