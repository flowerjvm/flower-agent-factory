package io.github.flowerjvm.factory.application.certification;

import io.github.flowerjvm.factory.contracts.certification.CertificationArtifactLock;
import io.github.flowerjvm.factory.contracts.certification.CertificationInputLock;
import io.github.flowerjvm.factory.contracts.ids.CertificationId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/** Version-CAS snapshot of one certification bound to an immutable AGENT_PACK input lock. */
public record Certification(
        CertificationId certificationId,
        CertificationInputLock inputLock,
        CertificationArtifactLock inputLockArtifact,
        CertificationStatus status,
        Optional<CertificationArtifactLock> certificationManifest,
        Optional<CertificationArtifactLock> certificationEvidence,
        Optional<String> actionRunId,
        Optional<String> stableCode,
        Optional<Instant> issuedAt,
        Optional<Instant> expiresAt,
        Optional<Instant> revokedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public Certification {
        Objects.requireNonNull(certificationId, "certificationId");
        requireExactIdentity(certificationId.value(), "certificationId");
        Objects.requireNonNull(inputLock, "inputLock");
        Objects.requireNonNull(inputLockArtifact, "inputLockArtifact");
        Objects.requireNonNull(status, "status");
        certificationManifest = Objects.requireNonNull(certificationManifest, "certificationManifest");
        certificationEvidence = Objects.requireNonNull(certificationEvidence, "certificationEvidence");
        actionRunId = requireOptionalText(actionRunId, "actionRunId");
        stableCode = requireOptionalStableCode(stableCode);
        issuedAt = requireOptionalCanonicalInstant(issuedAt, "issuedAt");
        expiresAt = requireOptionalCanonicalInstant(expiresAt, "expiresAt");
        revokedAt = requireOptionalCanonicalInstant(revokedAt, "revokedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        createdAt = requireCanonicalInstant(createdAt, "createdAt");
        updatedAt = requireCanonicalInstant(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        validateLifecycle(
                status,
                certificationManifest,
                certificationEvidence,
                actionRunId,
                stableCode,
                issuedAt,
                expiresAt,
                revokedAt,
                createdAt,
                updatedAt);
    }

    public static Certification requested(
            CertificationId certificationId,
            CertificationInputLock inputLock,
            CertificationArtifactLock inputLockArtifact,
            Instant createdAt) {
        return new Certification(
                certificationId,
                inputLock,
                inputLockArtifact,
                CertificationStatus.REQUESTED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                createdAt,
                createdAt);
    }

    public Certification certify(
            CertificationArtifactLock manifest,
            CertificationArtifactLock evidence,
            String owningActionRunId,
            Instant certifiedAt,
            Optional<Instant> expiresAt) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(evidence, "evidence");
        owningActionRunId = requireText(owningActionRunId, "owningActionRunId", 128);
        certifiedAt = requireCanonicalInstant(certifiedAt, "certifiedAt");
        expiresAt = requireOptionalCanonicalInstant(expiresAt, "expiresAt");
        if (status != CertificationStatus.REQUESTED) {
            throw new IllegalStateException("only a REQUESTED Certification can be certified");
        }
        if (certifiedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("certifiedAt must not be before the last update");
        }
        Instant terminalCertifiedAt = certifiedAt;
        expiresAt.ifPresent(expiry -> {
            if (!expiry.isAfter(terminalCertifiedAt)) {
                throw new IllegalArgumentException("expiresAt must be after certifiedAt");
            }
        });
        return new Certification(
                certificationId,
                inputLock,
                inputLockArtifact,
                CertificationStatus.CERTIFIED,
                Optional.of(manifest),
                Optional.of(evidence),
                Optional.of(owningActionRunId),
                Optional.empty(),
                Optional.of(createdAt),
                expiresAt,
                Optional.empty(),
                version + 1,
                createdAt,
                certifiedAt);
    }

    public Certification reject(String code, Instant rejectedAt) {
        code = requireStableCode(code);
        rejectedAt = requireCanonicalInstant(rejectedAt, "rejectedAt");
        if (status != CertificationStatus.REQUESTED) {
            throw new IllegalStateException("only a REQUESTED Certification can be rejected");
        }
        if (rejectedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("rejectedAt must not be before the last update");
        }
        return new Certification(
                certificationId,
                inputLock,
                inputLockArtifact,
                CertificationStatus.NOT_CERTIFIED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(code),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                version + 1,
                createdAt,
                rejectedAt);
    }

    public Certification revoke(String code, Instant revokedAt) {
        code = requireStableCode(code);
        revokedAt = requireCanonicalInstant(revokedAt, "revokedAt");
        if (status != CertificationStatus.CERTIFIED) {
            throw new IllegalStateException("only a CERTIFIED Certification can be revoked");
        }
        if (revokedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("revokedAt must not be before the last update");
        }
        return new Certification(
                certificationId,
                inputLock,
                inputLockArtifact,
                CertificationStatus.REVOKED,
                certificationManifest,
                certificationEvidence,
                actionRunId,
                Optional.of(code),
                issuedAt,
                expiresAt,
                Optional.of(revokedAt),
                version + 1,
                createdAt,
                revokedAt);
    }

    private static void validateLifecycle(
            CertificationStatus status,
            Optional<CertificationArtifactLock> manifest,
            Optional<CertificationArtifactLock> evidence,
            Optional<String> actionRunId,
            Optional<String> stableCode,
            Optional<Instant> issuedAt,
            Optional<Instant> expiresAt,
            Optional<Instant> revokedAt,
            Instant createdAt,
            Instant updatedAt) {
        switch (status) {
            case REQUESTED -> {
                if (manifest.isPresent()
                        || evidence.isPresent()
                        || actionRunId.isPresent()
                        || stableCode.isPresent()
                        || issuedAt.isPresent()
                        || expiresAt.isPresent()
                        || revokedAt.isPresent()) {
                    throw new IllegalArgumentException("REQUESTED Certification must not contain a result");
                }
            }
            case CERTIFIED -> {
                if (manifest.isEmpty()
                        || evidence.isEmpty()
                        || actionRunId.isEmpty()
                        || issuedAt.isEmpty()
                        || stableCode.isPresent()
                        || revokedAt.isPresent()) {
                    throw new IllegalArgumentException("CERTIFIED Certification has an invalid result shape");
                }
            }
            case NOT_CERTIFIED -> {
                if (stableCode.isEmpty()
                        || manifest.isPresent()
                        || evidence.isPresent()
                        || actionRunId.isPresent()
                        || issuedAt.isPresent()
                        || expiresAt.isPresent()
                        || revokedAt.isPresent()) {
                    throw new IllegalArgumentException("NOT_CERTIFIED Certification has an invalid result shape");
                }
            }
            case REVOKED -> {
                if (manifest.isEmpty()
                        || evidence.isEmpty()
                        || actionRunId.isEmpty()
                        || stableCode.isEmpty()
                        || issuedAt.isEmpty()
                        || revokedAt.isEmpty()) {
                    throw new IllegalArgumentException("REVOKED Certification must preserve issuance evidence");
                }
            }
        }
        issuedAt.ifPresent(value -> {
            if (!value.equals(createdAt)) {
                throw new IllegalArgumentException("issuedAt must equal the trusted REQUESTED createdAt");
            }
        });
        if (issuedAt.isPresent() && expiresAt.isPresent() && !expiresAt.orElseThrow().isAfter(issuedAt.orElseThrow())) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        if (issuedAt.isPresent() && status == CertificationStatus.CERTIFIED
                && updatedAt.isBefore(issuedAt.orElseThrow())) {
            throw new IllegalArgumentException("CERTIFIED updatedAt must not be before issuedAt");
        }
        if (expiresAt.isPresent() && status == CertificationStatus.CERTIFIED
                && !expiresAt.orElseThrow().isAfter(updatedAt)) {
            throw new IllegalArgumentException("CERTIFIED expiresAt must be after updatedAt");
        }
        if (revokedAt.isPresent() && !updatedAt.equals(revokedAt.orElseThrow())) {
            throw new IllegalArgumentException("REVOKED updatedAt must equal revokedAt");
        }
        if (issuedAt.isPresent() && revokedAt.isPresent()
                && revokedAt.orElseThrow().isBefore(issuedAt.orElseThrow())) {
            throw new IllegalArgumentException("revokedAt must not be before issuedAt");
        }
    }

    private static Optional<String> requireOptionalText(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(text -> requireText(text, name, 128));
        return value;
    }

    private static Optional<String> requireOptionalStableCode(Optional<String> value) {
        Objects.requireNonNull(value, "stableCode");
        value.ifPresent(Certification::requireStableCode);
        return value;
    }

    private static Optional<Instant> requireOptionalCanonicalInstant(Optional<Instant> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(instant -> requireCanonicalInstant(instant, name));
        return value;
    }

    private static Instant requireCanonicalInstant(Instant value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.equals(value.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException(name + " must use microsecond precision");
        }
        return value;
    }

    private static String requireStableCode(String value) {
        value = requireText(value, "stableCode", 128);
        if (!value.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("stableCode must be a bounded uppercase stable code");
        }
        return value;
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded non-control text");
        }
        return value;
    }

    private static void requireExactIdentity(String value, String name) {
        requireText(value, name, 128);
        if (value.matches("(?i).*(?:^|[:/@._-])(latest|head)(?:$|[:/@._-]).*")) {
            throw new IllegalArgumentException(name + " must not be floating");
        }
    }
}
