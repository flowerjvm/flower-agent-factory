CREATE TABLE factory_reference_assembly_release_intent (
    operation_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    reference_assembly_id VARCHAR(128) NOT NULL,
    assembly_manifest_hash CHAR(64) NOT NULL,
    inspection_report_hash CHAR(64) NOT NULL,
    release_decision_point_id VARCHAR(128) NOT NULL,
    release_subject_hash CHAR(64) NOT NULL,
    expected_reference_assembly_version BIGINT NOT NULL,
    action_run_id VARCHAR(64) NOT NULL,
    attempt_token_hash CHAR(64) NOT NULL,
    deadline_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL,
    claim_token VARCHAR(128) NULL,
    lease_until TIMESTAMP(6) WITH TIME ZONE NULL,
    attempt_count INTEGER NOT NULL,
    last_code VARCHAR(128) NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_factory_reference_release_intent_action_run
        UNIQUE (action_run_id),
    CONSTRAINT uq_factory_reference_release_intent_attempt
        UNIQUE (
            tenant_id, reference_assembly_id, expected_reference_assembly_version),
    CONSTRAINT fk_factory_reference_release_intent_assembly
        FOREIGN KEY (tenant_id, reference_assembly_id)
        REFERENCES factory_reference_assembly (tenant_id, reference_assembly_id),
    CONSTRAINT fk_factory_reference_release_intent_action_run
        FOREIGN KEY (tenant_id, action_run_id)
        REFERENCES action_run (tenant_id, run_id),
    CONSTRAINT ck_factory_reference_release_intent_versions
        CHECK (
            expected_reference_assembly_version >= 0
            AND attempt_count >= 0
            AND version >= 0),
    CONSTRAINT ck_factory_reference_release_intent_hashes
        CHECK (
            CHAR_LENGTH(assembly_manifest_hash) = 64
            AND CHAR_LENGTH(inspection_report_hash) = 64
            AND CHAR_LENGTH(release_subject_hash) = 64
            AND CHAR_LENGTH(attempt_token_hash) = 64
            AND assembly_manifest_hash = LOWER(assembly_manifest_hash)
            AND inspection_report_hash = LOWER(inspection_report_hash)
            AND release_subject_hash = LOWER(release_subject_hash)
            AND attempt_token_hash = LOWER(attempt_token_hash)),
    CONSTRAINT ck_factory_reference_release_intent_time
        CHECK (
            deadline_at > created_at
            AND updated_at >= created_at
            AND (lease_until IS NULL OR lease_until > updated_at)),
    CONSTRAINT ck_factory_reference_release_intent_lifecycle
        CHECK (
            (status = 'PENDING'
                AND claim_token IS NULL
                AND lease_until IS NULL
                AND attempt_count = 0
                AND last_code IS NULL
                AND version = 0
                AND updated_at = created_at)
            OR
            (status = 'RUNNING'
                AND claim_token IS NOT NULL
                AND lease_until IS NOT NULL
                AND attempt_count > 0
                AND version >= attempt_count
                AND ((last_code IS NULL AND version = attempt_count)
                    OR (last_code IS NOT NULL AND version > attempt_count)))
            OR
            (status = 'UNCERTAIN'
                AND claim_token IS NULL
                AND lease_until IS NOT NULL
                AND attempt_count > 0
                AND last_code IS NOT NULL
                AND version > attempt_count)
            OR
            (status IN ('COMPLETED', 'ORPHANED', 'ORPHANED_BEFORE_WAITING')
                AND claim_token IS NULL
                AND lease_until IS NULL
                AND attempt_count > 0
                AND last_code IS NOT NULL
                AND version > attempt_count)
        )
);

CREATE INDEX idx_factory_reference_release_intent_claim
    ON factory_reference_assembly_release_intent (
        status, lease_until, created_at, operation_id);

CREATE INDEX idx_factory_reference_release_intent_recovery
    ON factory_reference_assembly_release_intent (
        status, lease_until, operation_id);

CREATE INDEX idx_factory_reference_release_intent_assembly
    ON factory_reference_assembly_release_intent (
        tenant_id, reference_assembly_id,
        expected_reference_assembly_version, created_at, operation_id);
