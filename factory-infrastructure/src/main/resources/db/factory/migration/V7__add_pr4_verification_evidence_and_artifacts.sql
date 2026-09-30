-- V4 allowed more than one REQUESTED/RUNNING verification for the same immutable
-- candidate and gate. V7 must not pick a winner or invent terminal evidence for such
-- rows. Fail before making any schema change and expose a stable operator-facing code.
-- The dynamic cast is deliberately portable across H2 and PostgreSQL: zero conflicts
-- casts "0", while any conflict reports the stable text below as a conversion error.
SELECT CAST(
    CASE
        WHEN COUNT(*) = 0 THEN '0'
        ELSE 'V7_DUPLICATE_ACTIVE_VERIFICATION_RUNS_REQUIRE_OPERATOR_RESOLUTION'
    END AS INTEGER)
FROM (
    SELECT tenant_id, build_session_id, candidate_id, candidate_hash, gate_profile
    FROM factory_verification_run
    WHERE status IN ('REQUESTED', 'RUNNING')
    GROUP BY tenant_id, build_session_id, candidate_id, candidate_hash, gate_profile
    HAVING COUNT(*) > 1
) legacy_duplicate_active_verification;

CREATE TABLE factory_artifact (
    tenant_id VARCHAR(128) NOT NULL,
    artifact_ref VARCHAR(1024) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    media_type VARCHAR(255) NOT NULL,
    content_size BIGINT NOT NULL,
    content_base64 TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_factory_artifact
        PRIMARY KEY (tenant_id, artifact_ref),
    CONSTRAINT ck_factory_artifact_content_size
        CHECK (content_size >= 0 AND content_size <= 33554432),
    CONSTRAINT ck_factory_artifact_content_hash
        CHECK (CHAR_LENGTH(content_hash) = 64)
);

CREATE INDEX idx_factory_artifact_tenant_hash
    ON factory_artifact (tenant_id, content_hash);

ALTER TABLE factory_verification_run
    ADD COLUMN result_manifest_hash CHAR(64) NULL;

ALTER TABLE factory_verification_run
    ADD COLUMN terminal_code VARCHAR(255) NULL;

ALTER TABLE factory_verification_run
    ADD COLUMN disposition VARCHAR(64) NULL;

ALTER TABLE factory_verification_run
    ADD COLUMN active_key VARCHAR(1024) NULL;

-- Rows created before PR 4 did not persist these terminal evidence fields. Keep them
-- readable with an explicit compatibility marker; new terminal transitions always write
-- the actual immutable manifest hash, stable code and disposition.
UPDATE factory_verification_run
SET result_manifest_hash = '0000000000000000000000000000000000000000000000000000000000000000',
    terminal_code = CASE
        WHEN status = 'PASSED' THEN 'LEGACY_VERIFICATION_PASSED'
        ELSE 'LEGACY_VERIFICATION_FAILED'
    END,
    disposition = CASE
        WHEN status = 'PASSED' THEN 'REVIEW_ELIGIBLE'
        ELSE 'REPAIR_REQUIRED'
    END
WHERE status IN ('PASSED', 'FAILED');

UPDATE factory_verification_run
SET active_key = CHAR_LENGTH(tenant_id) || ':' || tenant_id
        || CHAR_LENGTH(build_session_id) || ':' || build_session_id
        || CHAR_LENGTH(candidate_id) || ':' || candidate_id
        || CHAR_LENGTH(candidate_hash) || ':' || candidate_hash
        || CHAR_LENGTH(gate_profile) || ':' || gate_profile
WHERE status IN ('REQUESTED', 'RUNNING');

ALTER TABLE factory_verification_run
    DROP CONSTRAINT ck_factory_verification_run_lifecycle;

ALTER TABLE factory_verification_run
    ADD CONSTRAINT ck_factory_verification_run_lifecycle
    CHECK (
        (status = 'REQUESTED'
            AND started_at IS NULL
            AND completed_at IS NULL
            AND result_manifest_ref IS NULL
            AND result_manifest_hash IS NULL
            AND terminal_code IS NULL
            AND disposition IS NULL
            AND active_key IS NOT NULL)
        OR
        (status = 'RUNNING'
            AND started_at IS NOT NULL
            AND completed_at IS NULL
            AND result_manifest_ref IS NULL
            AND result_manifest_hash IS NULL
            AND terminal_code IS NULL
            AND disposition IS NULL
            AND active_key IS NOT NULL)
        OR
        (status IN ('PASSED', 'FAILED')
            AND started_at IS NOT NULL
            AND completed_at IS NOT NULL
            AND result_manifest_ref IS NOT NULL
            AND result_manifest_hash IS NOT NULL
            AND terminal_code IS NOT NULL
            AND disposition IS NOT NULL
            AND active_key IS NULL)
    );

ALTER TABLE factory_verification_run
    ADD CONSTRAINT ck_factory_verification_run_result_hash
    CHECK (result_manifest_hash IS NULL OR CHAR_LENGTH(result_manifest_hash) = 64);

ALTER TABLE factory_verification_run
    ADD CONSTRAINT ck_factory_verification_run_terminal_code
    CHECK (terminal_code IS NULL OR CHAR_LENGTH(TRIM(terminal_code)) > 0);

ALTER TABLE factory_verification_run
    ADD CONSTRAINT ck_factory_verification_run_disposition
    CHECK (
        disposition IS NULL
        OR (status = 'PASSED' AND disposition = 'REVIEW_ELIGIBLE')
        OR (status = 'FAILED' AND disposition IN ('REPAIR_REQUIRED', 'BLOCKED'))
    );

ALTER TABLE factory_verification_run
    ADD CONSTRAINT uq_factory_verification_run_active_key
    UNIQUE (active_key);
