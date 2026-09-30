CREATE TABLE factory_candidate_version (
    candidate_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    build_session_id VARCHAR(128) NOT NULL,
    parent_candidate_id VARCHAR(128) NULL,
    source_manifest_ref VARCHAR(1024) NOT NULL,
    source_hash CHAR(64) NOT NULL,
    dependency_lock_ref VARCHAR(1024) NOT NULL,
    dependency_lock_hash CHAR(64) NOT NULL,
    toolchain_lock_ref VARCHAR(1024) NOT NULL,
    toolchain_lock_hash CHAR(64) NOT NULL,
    status VARCHAR(64) NOT NULL,
    created_by_work_order_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_factory_candidate_version_tenant_id
        UNIQUE (tenant_id, candidate_id),
    CONSTRAINT uq_factory_candidate_version_session_id
        UNIQUE (tenant_id, build_session_id, candidate_id),
    CONSTRAINT uq_factory_candidate_version_work_order
        UNIQUE (tenant_id, build_session_id, created_by_work_order_id),
    CONSTRAINT uq_factory_candidate_version_locked_hashes
        UNIQUE (tenant_id, build_session_id, candidate_id, source_hash, toolchain_lock_hash),
    CONSTRAINT fk_factory_candidate_version_build_session
        FOREIGN KEY (tenant_id, build_session_id)
        REFERENCES factory_build_session (tenant_id, build_session_id),
    CONSTRAINT fk_factory_candidate_version_parent
        FOREIGN KEY (tenant_id, build_session_id, parent_candidate_id)
        REFERENCES factory_candidate_version (tenant_id, build_session_id, candidate_id),
    CONSTRAINT fk_factory_candidate_version_work_order
        FOREIGN KEY (tenant_id, build_session_id, created_by_work_order_id)
        REFERENCES factory_work_order (tenant_id, build_session_id, work_order_id)
);

CREATE INDEX idx_factory_candidate_version_session_created
    ON factory_candidate_version (tenant_id, build_session_id, created_at);

CREATE INDEX idx_factory_candidate_version_source_hash
    ON factory_candidate_version (tenant_id, source_hash);

CREATE TABLE factory_verification_run (
    verification_run_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    build_session_id VARCHAR(128) NOT NULL,
    candidate_id VARCHAR(128) NOT NULL,
    candidate_hash CHAR(64) NOT NULL,
    gate_profile VARCHAR(255) NOT NULL,
    toolchain_lock_hash CHAR(64) NOT NULL,
    fixture_set_hash CHAR(64) NOT NULL,
    status VARCHAR(64) NOT NULL,
    result_manifest_ref VARCHAR(1024) NULL,
    started_at TIMESTAMP WITH TIME ZONE NULL,
    completed_at TIMESTAMP WITH TIME ZONE NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_factory_verification_run_tenant_id
        UNIQUE (tenant_id, verification_run_id),
    CONSTRAINT fk_factory_verification_run_candidate_lock
        FOREIGN KEY (
            tenant_id, build_session_id, candidate_id, candidate_hash, toolchain_lock_hash)
        REFERENCES factory_candidate_version (
            tenant_id, build_session_id, candidate_id, source_hash, toolchain_lock_hash),
    CONSTRAINT ck_factory_verification_run_version
        CHECK (version >= 0),
    CONSTRAINT ck_factory_verification_run_updated_at
        CHECK (updated_at >= created_at),
    CONSTRAINT ck_factory_verification_run_lifecycle
        CHECK (
            (status = 'REQUESTED'
                AND started_at IS NULL
                AND completed_at IS NULL
                AND result_manifest_ref IS NULL)
            OR
            (status = 'RUNNING'
                AND started_at IS NOT NULL
                AND completed_at IS NULL
                AND result_manifest_ref IS NULL)
            OR
            (status IN ('PASSED', 'FAILED')
                AND started_at IS NOT NULL
                AND completed_at IS NOT NULL
                AND result_manifest_ref IS NOT NULL)
        ),
    CONSTRAINT ck_factory_verification_run_start_time
        CHECK (started_at IS NULL OR started_at >= created_at),
    CONSTRAINT ck_factory_verification_run_completion_time
        CHECK (completed_at IS NULL OR (completed_at >= started_at AND updated_at = completed_at))
);

CREATE INDEX idx_factory_verification_run_candidate_gate
    ON factory_verification_run (
        tenant_id, build_session_id, candidate_id, candidate_hash, gate_profile, created_at);
