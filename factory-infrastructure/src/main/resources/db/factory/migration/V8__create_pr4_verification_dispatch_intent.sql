CREATE TABLE factory_verification_dispatch_intent (
    operation_id VARCHAR(255) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    verification_run_id VARCHAR(128) NOT NULL,
    candidate_id VARCHAR(128) NOT NULL,
    expected_verification_run_version BIGINT NOT NULL,
    action_run_id VARCHAR(64) NOT NULL,
    attempt_token_hash CHAR(64) NOT NULL,
    deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL,
    claim_token VARCHAR(128) NULL,
    lease_until TIMESTAMP WITH TIME ZONE NULL,
    attempt_count INTEGER NOT NULL,
    last_code VARCHAR(128) NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_factory_verification_dispatch_action_run UNIQUE (action_run_id),
    CONSTRAINT uq_factory_verification_dispatch_attempt
        UNIQUE (tenant_id, verification_run_id, expected_verification_run_version),
    CONSTRAINT fk_factory_verification_dispatch_verification
        FOREIGN KEY (tenant_id, verification_run_id)
        REFERENCES factory_verification_run (tenant_id, verification_run_id),
    CONSTRAINT fk_factory_verification_dispatch_action_run
        FOREIGN KEY (action_run_id) REFERENCES action_run (run_id),
    CONSTRAINT ck_factory_verification_dispatch_version
        CHECK (version >= 0 AND attempt_count >= 0),
    CONSTRAINT ck_factory_verification_dispatch_token_hash
        CHECK (CHAR_LENGTH(attempt_token_hash) = 64),
    CONSTRAINT ck_factory_verification_dispatch_time
        CHECK (updated_at >= created_at AND deadline_at >= created_at),
    CONSTRAINT ck_factory_verification_dispatch_lifecycle
        CHECK (
            (status = 'PENDING' AND claim_token IS NULL AND lease_until IS NULL
                AND attempt_count = 0 AND last_code IS NULL)
            OR
            (status = 'RUNNING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL
                AND attempt_count > 0)
            OR
            (status = 'UNCERTAIN' AND claim_token IS NULL AND lease_until IS NOT NULL
                AND attempt_count > 0 AND last_code IS NOT NULL)
            OR
            (status IN ('COMPLETED', 'ORPHANED') AND claim_token IS NULL AND lease_until IS NULL
                AND attempt_count > 0 AND last_code IS NOT NULL)
        )
);

CREATE INDEX idx_factory_verification_dispatch_claim
    ON factory_verification_dispatch_intent (status, lease_until, created_at);

CREATE INDEX idx_factory_verification_dispatch_verification
    ON factory_verification_dispatch_intent (tenant_id, verification_run_id, created_at);
