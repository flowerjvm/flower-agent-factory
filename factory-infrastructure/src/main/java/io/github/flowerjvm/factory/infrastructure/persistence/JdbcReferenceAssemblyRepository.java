package io.github.flowerjvm.factory.infrastructure.persistence;

import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.getOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.requireCas;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.requireSame;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setInstant;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.setOptionalText;
import static io.github.flowerjvm.factory.infrastructure.persistence.JdbcPersistenceSupport.withConnection;

import io.github.flowerjvm.factory.application.build.BuildSessionPhase;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssembly;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyRepository;
import io.github.flowerjvm.factory.application.referenceassembly.ReferenceAssemblyStatus;
import io.github.flowerjvm.factory.contracts.artifact.ArtifactReference;
import io.github.flowerjvm.factory.contracts.artifact.ContentHash;
import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.ids.BuildSessionId;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import io.github.flowerjvm.factory.contracts.ids.DecisionPointId;
import io.github.flowerjvm.factory.contracts.ids.ReferenceAssemblyId;
import io.github.flowerjvm.factory.contracts.ids.TenantId;
import io.github.flowerjvm.factory.contracts.referenceassembly.ReferenceAssemblyRequirement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC version-CAS ledger for the concrete Reference Assembly ProductLine. */
public final class JdbcReferenceAssemblyRepository implements ReferenceAssemblyRepository {
    private static final String INSERT = """
            INSERT INTO factory_reference_assembly (
                reference_assembly_id, tenant_id, build_session_id,
                requirement_ref, requirement_hash,
                consumer_contract_ref, consumer_contract_hash,
                host_fixture_ref, host_fixture_hash,
                policy_snapshot_ref, policy_snapshot_hash,
                component_certification_id, component_candidate_hash,
                component_certification_manifest_ref, component_certification_manifest_hash,
                status,
                assembly_manifest_ref, assembly_manifest_hash,
                inspection_report_ref, inspection_report_hash,
                release_manifest_ref, release_manifest_hash,
                release_decision_point_id, release_subject_hash,
                release_action_run_id, stable_code,
                version, created_at, updated_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND = """
            SELECT * FROM factory_reference_assembly
            WHERE tenant_id = ? AND reference_assembly_id = ?
            """;

    private static final String FIND_BY_BUILD_SESSION = """
            SELECT * FROM factory_reference_assembly
            WHERE tenant_id = ? AND build_session_id = ?
            """;

    private static final String FIND_RELEASED_BY_COMPONENT_CERTIFICATION = """
            SELECT * FROM factory_reference_assembly
            WHERE tenant_id = ? AND component_certification_id = ? AND status = 'RELEASED'
            ORDER BY created_at ASC, reference_assembly_id ASC
            """;

    private static final String FIND_RELEASED_BY_COMPONENT_CERTIFICATION_BOUNDED = """
            SELECT * FROM factory_reference_assembly
            WHERE tenant_id = ? AND component_certification_id = ? AND status = 'RELEASED'
            ORDER BY created_at ASC, reference_assembly_id ASC
            FETCH FIRST ? ROWS ONLY
            """;

    private static final String UPDATE = """
            UPDATE factory_reference_assembly
            SET status = ?,
                assembly_manifest_ref = ?, assembly_manifest_hash = ?,
                inspection_report_ref = ?, inspection_report_hash = ?,
                release_manifest_ref = ?, release_manifest_hash = ?,
                release_decision_point_id = ?, release_subject_hash = ?,
                release_action_run_id = ?, stable_code = ?,
                version = ?, updated_at = ?
            WHERE tenant_id = ? AND reference_assembly_id = ?
              AND version = ? AND status = ?
            """;

    private static final String TRUSTED_INPUTS = """
            SELECT
                b.product_line_id AS build_product_line_id,
                b.status AS build_status,
                b.current_phase AS build_current_phase,
                b.requirements_artifact_ref AS build_requirement_ref,
                b.requirements_hash AS build_requirement_hash,
                b.current_certification_id AS build_current_certification_id,
                b.cancellation_requested_at AS build_cancellation_requested_at,
                b.deadline_at AS build_deadline_at,
                c.status AS certification_status,
                c.candidate_hash AS certification_candidate_hash,
                c.certification_manifest_ref AS certification_manifest_ref,
                c.certification_manifest_hash AS certification_manifest_hash,
                c.expires_at AS certification_expires_at
            FROM factory_build_session b
            CROSS JOIN factory_certification c
            WHERE b.tenant_id = ? AND b.build_session_id = ?
              AND c.tenant_id = ? AND c.certification_id = ?
            FOR UPDATE
            """;

    private final DataSource dataSource;

    public JdbcReferenceAssemblyRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void create(ReferenceAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        if (assembly.status() != ReferenceAssemblyStatus.REQUESTED || assembly.version() != 0) {
            throw new IllegalArgumentException(
                    "ReferenceAssembly create accepts only version-zero REQUESTED state");
        }
        withConnection(dataSource, "create ReferenceAssembly", connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                validateTrustedInputs(connection, assembly);
                try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                    bindInsert(statement, assembly);
                    statement.executeUpdate();
                }
                connection.commit();
                return null;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    @Override
    public Optional<ReferenceAssembly> find(
            TenantId tenantId, ReferenceAssemblyId referenceAssemblyId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(referenceAssemblyId, "referenceAssemblyId");
        return withConnection(dataSource, "find ReferenceAssembly", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, referenceAssemblyId.value());
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(map(rows)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Optional<ReferenceAssembly> findByBuildSession(
            TenantId tenantId, BuildSessionId buildSessionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(buildSessionId, "buildSessionId");
        return withConnection(dataSource, "find ReferenceAssembly by BuildSession", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND_BY_BUILD_SESSION)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, buildSessionId.value());
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(map(rows)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<ReferenceAssembly> findReleasedByComponentCertification(
            TenantId tenantId, CertificationId componentCertificationId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(componentCertificationId, "componentCertificationId");
        return withConnection(dataSource, "find released ReferenceAssemblies by component", connection -> {
            try (PreparedStatement statement =
                            connection.prepareStatement(FIND_RELEASED_BY_COMPONENT_CERTIFICATION)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, componentCertificationId.value());
                try (ResultSet rows = statement.executeQuery()) {
                    List<ReferenceAssembly> results = new ArrayList<>();
                    while (rows.next()) {
                        results.add(map(rows));
                    }
                    return List.copyOf(results);
                }
            }
        });
    }

    @Override
    public List<ReferenceAssembly> findReleasedByComponentCertification(
            TenantId tenantId, CertificationId componentCertificationId, int limit) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(componentCertificationId, "componentCertificationId");
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException(
                    "released-component provenance limit must be between 1 and 1000");
        }
        return withConnection(
                dataSource,
                "find bounded released ReferenceAssemblies by component",
                connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            FIND_RELEASED_BY_COMPONENT_CERTIFICATION_BOUNDED)) {
                        statement.setString(1, tenantId.value());
                        statement.setString(2, componentCertificationId.value());
                        statement.setInt(3, limit);
                        try (ResultSet rows = statement.executeQuery()) {
                            List<ReferenceAssembly> results = new ArrayList<>();
                            while (rows.next()) {
                                results.add(map(rows));
                            }
                            return List.copyOf(results);
                        }
                    }
                });
    }

    @Override
    public boolean compareAndSet(ReferenceAssembly expected, ReferenceAssembly next) {
        validateCas(expected, next, false);
        return withConnection(dataSource, "CAS ReferenceAssembly", connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                boolean updated = compareAndSetLocked(connection, expected, next);
                if (updated) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                return updated;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    /**
     * Release-only CAS boundary for the controlled infrastructure transaction.
     *
     * <p>The caller owns the supplied transaction and its commit or rollback. Package visibility
     * prevents application or host callers from bypassing the release transaction.
     */
    boolean compareAndSetReleased(
            Connection connection, ReferenceAssembly expected, ReferenceAssembly next)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                    "ReferenceAssembly release CAS requires a caller-owned transaction");
        }
        if (next == null || next.status() != ReferenceAssemblyStatus.RELEASED) {
            throw new IllegalArgumentException(
                    "controlled ReferenceAssembly release CAS accepts only RELEASED state");
        }
        validateCas(expected, next, true);
        return compareAndSetLocked(connection, expected, next);
    }

    private static boolean compareAndSetLocked(
            Connection connection, ReferenceAssembly expected, ReferenceAssembly next)
            throws SQLException {
        Optional<ReferenceAssembly> canonical = find(
                connection,
                expected.tenantId(),
                expected.referenceAssemblyId(),
                true);
        if (canonical.filter(expected::equals).isEmpty()) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(UPDATE)) {
            int index = 1;
            statement.setString(index++, next.status().name());
            index = bindOptionalLock(statement, index, next.assemblyManifest());
            index = bindOptionalLock(statement, index, next.inspectionReport());
            index = bindOptionalLock(statement, index, next.releaseManifest());
            setOptionalText(
                    statement,
                    index++,
                    next.releaseDecisionPointId().map(DecisionPointId::value));
            setOptionalText(
                    statement,
                    index++,
                    next.releaseSubjectHash().map(ContentHash::sha256));
            setOptionalText(statement, index++, next.releaseActionRunId());
            setOptionalText(statement, index++, next.stableCode());
            statement.setLong(index++, next.version());
            setInstant(statement, index++, next.updatedAt());
            statement.setString(index++, expected.tenantId().value());
            statement.setString(index++, expected.referenceAssemblyId().value());
            statement.setLong(index++, expected.version());
            statement.setString(index, expected.status().name());
            return statement.executeUpdate() == 1;
        }
    }

    static Optional<ReferenceAssembly> find(
            Connection connection,
            TenantId tenantId,
            ReferenceAssemblyId referenceAssemblyId,
            boolean forUpdate) throws SQLException {
        String sql = forUpdate ? FIND + " FOR UPDATE" : FIND;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId.value());
            statement.setString(2, referenceAssemblyId.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        }
    }

    private static void validateTrustedInputs(
            Connection connection, ReferenceAssembly assembly) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRUSTED_INPUTS)) {
            statement.setString(1, assembly.tenantId().value());
            statement.setString(2, assembly.buildSessionId().value());
            statement.setString(3, assembly.tenantId().value());
            statement.setString(4, assembly.componentCertificationId().value());
            try (ResultSet row = statement.executeQuery()) {
                requireTrusted(row.next(), "BuildSession or component Certification is missing");
                requireTrusted(
                        ReferenceAssemblyRequirement.PRODUCT_LINE_ID.value()
                                .equals(row.getString("build_product_line_id")),
                        "BuildSession is not owned by the reference-assembly ProductLine");
                requireTrusted(
                        "RUNNING".equals(row.getString("build_status"))
                                && BuildSessionPhase.UNDERSTAND_CUSTOMER.id()
                                        .equals(row.getString("build_current_phase")),
                        "BuildSession is not at the exact reference-assembly request boundary");
                requireTrusted(
                        assembly.requirement().reference().value()
                                        .equals(row.getString("build_requirement_ref"))
                                && assembly.requirement().hash().sha256()
                                        .equals(row.getString("build_requirement_hash")),
                        "BuildSession requirement lock differs");
                requireTrusted(
                        row.getString("build_current_certification_id") == null,
                        "upstream component Certification must not occupy currentCertificationId");
                requireTrusted(
                        row.getTimestamp("build_cancellation_requested_at") == null,
                        "BuildSession cancellation was requested");
                requireTrusted(
                        row.getTimestamp("build_deadline_at").toInstant().isAfter(assembly.createdAt()),
                        "ReferenceAssembly request is at or after the BuildSession deadline");
                requireTrusted(
                        "CERTIFIED".equals(row.getString("certification_status")),
                        "component Certification is not currently CERTIFIED");
                requireTrusted(
                        assembly.componentCandidateHash().sha256()
                                .equals(row.getString("certification_candidate_hash")),
                        "component candidate hash differs");
                requireTrusted(
                        assembly.componentCertificationManifest().reference().value()
                                        .equals(row.getString("certification_manifest_ref"))
                                && assembly.componentCertificationManifest().hash().sha256()
                                        .equals(row.getString("certification_manifest_hash")),
                        "component Certification manifest differs");
                Timestamp expiry = row.getTimestamp("certification_expires_at");
                requireTrusted(
                        expiry == null || expiry.toInstant().isAfter(assembly.createdAt()),
                        "component Certification is expired at request time");
            }
        } catch (FactoryPersistenceException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw corrupt("ReferenceAssembly trusted input validation failed", invalid);
        }
    }

    private static void validateCas(
            ReferenceAssembly expected, ReferenceAssembly next, boolean releaseAllowed) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        if (!releaseAllowed && next.status() == ReferenceAssemblyStatus.RELEASED) {
            throw new IllegalArgumentException(
                    "RELEASED ReferenceAssembly requires the controlled release transaction");
        }
        requireCas(expected.version(), next.version());
        requireSame(expected.referenceAssemblyId().equals(next.referenceAssemblyId()),
                "referenceAssemblyId");
        requireSame(expected.tenantId().equals(next.tenantId()), "tenantId");
        requireSame(expected.buildSessionId().equals(next.buildSessionId()), "buildSessionId");
        requireSame(expected.requirement().equals(next.requirement()), "requirement");
        requireSame(expected.consumerContract().equals(next.consumerContract()), "consumerContract");
        requireSame(expected.hostFixture().equals(next.hostFixture()), "hostFixture");
        requireSame(expected.policySnapshot().equals(next.policySnapshot()), "policySnapshot");
        requireSame(expected.componentCertificationId().equals(next.componentCertificationId()),
                "componentCertificationId");
        requireSame(expected.componentCandidateHash().equals(next.componentCandidateHash()),
                "componentCandidateHash");
        requireSame(
                expected.componentCertificationManifest().equals(next.componentCertificationManifest()),
                "componentCertificationManifest");
        requireSame(expected.createdAt().equals(next.createdAt()), "createdAt");
        if (next.updatedAt().isBefore(expected.updatedAt())) {
            throw new IllegalArgumentException(
                    "ReferenceAssembly CAS updatedAt must not move backwards");
        }
        requireTransition(expected, next, releaseAllowed);
    }

    private static void requireTransition(
            ReferenceAssembly expected, ReferenceAssembly next, boolean releaseAllowed) {
        boolean rejected = next.status() == ReferenceAssemblyStatus.REJECTED
                && !expected.status().isTerminal()
                && expected.releaseActionRunId().isEmpty();
        boolean bindsReview = expected.status() == ReferenceAssemblyStatus.INSPECTED
                && next.status() == ReferenceAssemblyStatus.INSPECTED
                && expected.releaseDecisionPointId().isEmpty()
                && expected.releaseSubjectHash().isEmpty()
                && expected.releaseActionRunId().isEmpty()
                && next.releaseDecisionPointId().isPresent()
                && next.releaseSubjectHash().isPresent()
                && next.releaseActionRunId().isEmpty();
        boolean bindsAction = expected.status() == ReferenceAssemblyStatus.INSPECTED
                && next.status() == ReferenceAssemblyStatus.INSPECTED
                && expected.releaseDecisionPointId().isPresent()
                && expected.releaseSubjectHash().isPresent()
                && expected.releaseActionRunId().isEmpty()
                && expected.releaseDecisionPointId().equals(next.releaseDecisionPointId())
                && expected.releaseSubjectHash().equals(next.releaseSubjectHash())
                && next.releaseActionRunId().isPresent();
        boolean forward = switch (expected.status()) {
            case REQUESTED -> next.status() == ReferenceAssemblyStatus.COMPONENT_RESOLVED;
            case COMPONENT_RESOLVED -> next.status() == ReferenceAssemblyStatus.ASSEMBLED;
            case ASSEMBLED -> next.status() == ReferenceAssemblyStatus.INSPECTED;
            case INSPECTED -> releaseAllowed
                    && next.status() == ReferenceAssemblyStatus.RELEASED;
            case RELEASED, REJECTED -> false;
        };
        if (!forward && !rejected && !bindsReview && !bindsAction) {
            throw new IllegalArgumentException("unsupported ReferenceAssembly CAS transition");
        }

        requireSame(
                expected.assemblyManifest().isEmpty()
                        || expected.assemblyManifest().equals(next.assemblyManifest()),
                "existing assemblyManifest");
        requireSame(
                expected.inspectionReport().isEmpty()
                        || expected.inspectionReport().equals(next.inspectionReport()),
                "existing inspectionReport");
        if (rejected) {
            requireSame(
                    expected.assemblyManifest().equals(next.assemblyManifest()),
                    "assemblyManifest on rejection");
            boolean addsFailedInspection = expected.status() == ReferenceAssemblyStatus.ASSEMBLED
                    && expected.inspectionReport().isEmpty()
                    && next.inspectionReport().isPresent();
            requireSame(
                    expected.inspectionReport().equals(next.inspectionReport()) || addsFailedInspection,
                    "inspectionReport on rejection");
            requireSame(
                    expected.releaseDecisionPointId().equals(next.releaseDecisionPointId()),
                    "releaseDecisionPointId on rejection");
            requireSame(
                    expected.releaseSubjectHash().equals(next.releaseSubjectHash()),
                    "releaseSubjectHash on rejection");
            requireSame(
                    expected.releaseActionRunId().equals(next.releaseActionRunId()),
                    "releaseActionRunId on rejection");
        }
        if (expected.status() == ReferenceAssemblyStatus.INSPECTED
                && next.status() == ReferenceAssemblyStatus.RELEASED) {
            requireSame(
                    expected.releaseDecisionPointId().equals(next.releaseDecisionPointId()),
                    "releaseDecisionPointId on release");
            requireSame(
                    expected.releaseSubjectHash().equals(next.releaseSubjectHash()),
                    "releaseSubjectHash on release");
            requireSame(
                    expected.releaseActionRunId().equals(next.releaseActionRunId()),
                    "releaseActionRunId on release");
        }
    }

    private static void bindInsert(PreparedStatement statement, ReferenceAssembly assembly)
            throws SQLException {
        int index = 1;
        statement.setString(index++, assembly.referenceAssemblyId().value());
        statement.setString(index++, assembly.tenantId().value());
        statement.setString(index++, assembly.buildSessionId().value());
        index = bindLock(statement, index, assembly.requirement());
        index = bindLock(statement, index, assembly.consumerContract());
        index = bindLock(statement, index, assembly.hostFixture());
        index = bindLock(statement, index, assembly.policySnapshot());
        statement.setString(index++, assembly.componentCertificationId().value());
        statement.setString(index++, assembly.componentCandidateHash().sha256());
        index = bindLock(statement, index, assembly.componentCertificationManifest());
        statement.setString(index++, assembly.status().name());
        index = bindOptionalLock(statement, index, assembly.assemblyManifest());
        index = bindOptionalLock(statement, index, assembly.inspectionReport());
        index = bindOptionalLock(statement, index, assembly.releaseManifest());
        setOptionalText(statement, index++, assembly.releaseDecisionPointId().map(DecisionPointId::value));
        setOptionalText(statement, index++, assembly.releaseSubjectHash().map(ContentHash::sha256));
        setOptionalText(statement, index++, assembly.releaseActionRunId());
        setOptionalText(statement, index++, assembly.stableCode());
        statement.setLong(index++, assembly.version());
        setInstant(statement, index++, assembly.createdAt());
        setInstant(statement, index, assembly.updatedAt());
    }

    private static ReferenceAssembly map(ResultSet row) throws SQLException {
        try {
            return new ReferenceAssembly(
                    new ReferenceAssemblyId(row.getString("reference_assembly_id")),
                    new TenantId(row.getString("tenant_id")),
                    new BuildSessionId(row.getString("build_session_id")),
                    lock(row, "requirement_ref", "requirement_hash"),
                    lock(row, "consumer_contract_ref", "consumer_contract_hash"),
                    lock(row, "host_fixture_ref", "host_fixture_hash"),
                    lock(row, "policy_snapshot_ref", "policy_snapshot_hash"),
                    new CertificationId(row.getString("component_certification_id")),
                    new ContentHash(row.getString("component_candidate_hash")),
                    lock(
                            row,
                            "component_certification_manifest_ref",
                            "component_certification_manifest_hash"),
                    ReferenceAssemblyStatus.valueOf(row.getString("status")),
                    optionalLock(row, "assembly_manifest_ref", "assembly_manifest_hash"),
                    optionalLock(row, "inspection_report_ref", "inspection_report_hash"),
                    optionalLock(row, "release_manifest_ref", "release_manifest_hash"),
                    getOptionalText(row, "release_decision_point_id").map(DecisionPointId::new),
                    getOptionalText(row, "release_subject_hash").map(ContentHash::new),
                    getOptionalText(row, "release_action_run_id"),
                    getOptionalText(row, "stable_code"),
                    row.getLong("version"),
                    getInstant(row, "created_at"),
                    getInstant(row, "updated_at"));
        } catch (FactoryPersistenceException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw corrupt("ReferenceAssembly row failed strict reconstruction", invalid);
        }
    }

    private static int bindLock(
            PreparedStatement statement, int index, CertificationArtifactLock lock)
            throws SQLException {
        statement.setString(index++, lock.reference().value());
        statement.setString(index++, lock.hash().sha256());
        return index;
    }

    private static int bindOptionalLock(
            PreparedStatement statement,
            int index,
            Optional<CertificationArtifactLock> lock) throws SQLException {
        if (lock.isPresent()) {
            return bindLock(statement, index, lock.orElseThrow());
        }
        statement.setNull(index++, Types.VARCHAR);
        statement.setNull(index++, Types.CHAR);
        return index;
    }

    private static CertificationArtifactLock lock(
            ResultSet row, String referenceColumn, String hashColumn) throws SQLException {
        return new CertificationArtifactLock(
                new ArtifactReference(row.getString(referenceColumn)),
                new ContentHash(row.getString(hashColumn)));
    }

    private static Optional<CertificationArtifactLock> optionalLock(
            ResultSet row, String referenceColumn, String hashColumn) throws SQLException {
        String reference = row.getString(referenceColumn);
        String hash = row.getString(hashColumn);
        if (reference == null && hash == null) {
            return Optional.empty();
        }
        if (reference == null || hash == null) {
            throw corrupt(
                    "ReferenceAssembly row contains a partial artifact lock",
                    new IllegalStateException(referenceColumn));
        }
        return Optional.of(new CertificationArtifactLock(
                new ArtifactReference(reference), new ContentHash(hash)));
    }

    private static void requireTrusted(boolean condition, String message) {
        if (!condition) {
            throw corrupt(
                    "ReferenceAssembly trusted input validation failed: " + message,
                    new IllegalStateException(message));
        }
    }

    private static FactoryPersistenceException corrupt(String message, Throwable cause) {
        return new FactoryPersistenceException(message, cause);
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
