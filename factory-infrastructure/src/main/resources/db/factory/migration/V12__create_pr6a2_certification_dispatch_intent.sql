CREATE TABLE factory_certification_dispatch_intent (
    operation_id VARCHAR(255) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    certification_id VARCHAR(128) NOT NULL,
    input_lock_manifest_hash CHAR(64) NOT NULL,
    expected_certification_version BIGINT NOT NULL,
    action_run_id VARCHAR(64) NOT NULL,
    attempt_token_hash CHAR(64) NOT NULL,
    deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL,
    claim_token VARCHAR(256) NULL,
    lease_until TIMESTAMP WITH TIME ZONE NULL,
    attempt_count INTEGER NOT NULL,
    last_code VARCHAR(256) NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_factory_certification_dispatch_action_run
        UNIQUE (action_run_id),
    CONSTRAINT uq_factory_certification_dispatch_attempt
        UNIQUE (tenant_id, certification_id, expected_certification_version),
    CONSTRAINT fk_factory_certification_dispatch_certification
        FOREIGN KEY (tenant_id, certification_id)
        REFERENCES factory_certification (tenant_id, certification_id),
    CONSTRAINT fk_factory_certification_dispatch_action_run
        FOREIGN KEY (tenant_id, action_run_id)
        REFERENCES action_run (tenant_id, run_id),
    CONSTRAINT ck_factory_certification_dispatch_versions
        CHECK (
            expected_certification_version >= 0
            AND version >= 0
            AND attempt_count >= 0),
    CONSTRAINT ck_factory_certification_dispatch_hashes
        CHECK (
            CHAR_LENGTH(input_lock_manifest_hash) = 64
            AND CHAR_LENGTH(attempt_token_hash) = 64),
    CONSTRAINT ck_factory_certification_dispatch_time
        CHECK (
            updated_at >= created_at
            AND deadline_at >= created_at
            AND (lease_until IS NULL OR lease_until >= updated_at)),
    CONSTRAINT ck_factory_certification_dispatch_lifecycle
        CHECK (
            (status = 'PENDING'
                AND claim_token IS NULL
                AND lease_until IS NULL
                AND attempt_count = 0
                AND last_code IS NULL)
            OR
            (status = 'RUNNING'
                AND claim_token IS NOT NULL
                AND lease_until IS NOT NULL
                AND attempt_count > 0)
            OR
            (status = 'UNCERTAIN'
                AND claim_token IS NULL
                AND lease_until IS NOT NULL
                AND attempt_count > 0
                AND last_code IS NOT NULL)
            OR
            (status IN ('COMPLETED', 'ORPHANED', 'ORPHANED_BEFORE_WAITING')
                AND claim_token IS NULL
                AND lease_until IS NULL
                AND attempt_count > 0
                AND last_code IS NOT NULL)
        )
);

CREATE INDEX idx_factory_certification_dispatch_claim
    ON factory_certification_dispatch_intent (
        status, lease_until, created_at, operation_id);

CREATE INDEX idx_factory_certification_dispatch_recovery
    ON factory_certification_dispatch_intent (
        status, lease_until, operation_id);

CREATE INDEX idx_factory_certification_dispatch_certification
    ON factory_certification_dispatch_intent (
        tenant_id, certification_id, expected_certification_version, created_at);
