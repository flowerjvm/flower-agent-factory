CREATE TABLE factory_build_session (
    build_session_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    request_idempotency_key VARCHAR(255) NOT NULL,
    active_request_key VARCHAR(255) NULL,
    created_by VARCHAR(128) NOT NULL,
    status VARCHAR(64) NOT NULL,
    current_phase VARCHAR(64) NOT NULL,
    requirements_artifact_ref VARCHAR(1024) NOT NULL,
    requirements_hash CHAR(64) NOT NULL,
    selected_manager_worker_binding VARCHAR(255) NULL,
    selected_coding_worker_binding VARCHAR(255) NULL,
    current_blueprint_ref VARCHAR(1024) NULL,
    current_candidate_id VARCHAR(128) NULL,
    current_candidate_hash CHAR(64) NULL,
    current_certification_id VARCHAR(128) NULL,
    repair_round INTEGER NOT NULL,
    max_repair_rounds INTEGER NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
    cancellation_requested_at TIMESTAMP WITH TIME ZONE NULL,
    terminal_code VARCHAR(128) NULL,
    terminal_message VARCHAR(2048) NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_factory_build_session_tenant_id
        UNIQUE (tenant_id, build_session_id),
    CONSTRAINT uq_factory_build_session_active_request
        UNIQUE (tenant_id, active_request_key),
    CONSTRAINT ck_factory_build_session_active_request
        CHECK (
            (status IN ('SUCCEEDED', 'CANCELLED', 'FAILED') AND active_request_key IS NULL)
            OR
            (status NOT IN ('SUCCEEDED', 'CANCELLED', 'FAILED')
                AND active_request_key IS NOT NULL
                AND active_request_key = request_idempotency_key)
        ),
    CONSTRAINT ck_factory_build_session_rounds
        CHECK (repair_round >= 0 AND max_repair_rounds >= 0 AND repair_round <= max_repair_rounds),
    CONSTRAINT ck_factory_build_session_version
        CHECK (version >= 0)
);

CREATE INDEX idx_factory_build_session_request
    ON factory_build_session (tenant_id, request_idempotency_key, status);

CREATE TABLE factory_work_order (
    work_order_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    build_session_id VARCHAR(128) NOT NULL,
    phase VARCHAR(64) NOT NULL,
    purpose VARCHAR(1024) NOT NULL,
    revision INTEGER NOT NULL,
    supersedes_work_order_id VARCHAR(128) NULL,
    candidate_id VARCHAR(128) NULL,
    base_revision VARCHAR(255) NULL,
    instruction_artifact_ref VARCHAR(1024) NOT NULL,
    instruction_hash CHAR(64) NOT NULL,
    input_artifact_manifest_ref VARCHAR(1024) NOT NULL,
    input_manifest_hash CHAR(64) NOT NULL,
    workspace_ref VARCHAR(1024) NOT NULL,
    allowed_read_paths_json TEXT NOT NULL,
    allowed_write_paths_json TEXT NOT NULL,
    required_capabilities_json TEXT NOT NULL,
    expected_output_schema_id VARCHAR(255) NOT NULL,
    expected_output_schema_version VARCHAR(64) NOT NULL,
    policy_snapshot_ref VARCHAR(1024) NOT NULL,
    deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
    max_attempts INTEGER NOT NULL,
    logical_idempotency_key VARCHAR(255) NOT NULL,
    created_by_type VARCHAR(32) NOT NULL,
    created_by_ref VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_factory_work_order_tenant_id
        UNIQUE (tenant_id, work_order_id),
    CONSTRAINT uq_factory_work_order_session_id
        UNIQUE (tenant_id, build_session_id, work_order_id),
    CONSTRAINT uq_factory_work_order_logical_key
        UNIQUE (tenant_id, build_session_id, logical_idempotency_key),
    CONSTRAINT fk_factory_work_order_build_session
        FOREIGN KEY (tenant_id, build_session_id)
        REFERENCES factory_build_session (tenant_id, build_session_id),
    CONSTRAINT fk_factory_work_order_supersedes
        FOREIGN KEY (tenant_id, supersedes_work_order_id)
        REFERENCES factory_work_order (tenant_id, work_order_id),
    CONSTRAINT ck_factory_work_order_revision
        CHECK (revision > 0),
    CONSTRAINT ck_factory_work_order_attempts
        CHECK (max_attempts > 0)
);

CREATE INDEX idx_factory_work_order_session
    ON factory_work_order (tenant_id, build_session_id, created_at);

CREATE TABLE factory_dispatch_outbox (
    outbox_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    operation_type VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(128) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    operation_id VARCHAR(255) NOT NULL,
    payload_artifact_ref VARCHAR(1024) NOT NULL,
    status VARCHAR(32) NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempt_count INTEGER NOT NULL,
    last_code VARCHAR(128) NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_factory_dispatch_outbox_tenant_id
        UNIQUE (tenant_id, outbox_id),
    CONSTRAINT uq_factory_dispatch_outbox_operation
        UNIQUE (tenant_id, operation_id),
    CONSTRAINT ck_factory_dispatch_outbox_attempt_count
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_factory_dispatch_outbox_version
        CHECK (version >= 0)
);

CREATE INDEX idx_factory_dispatch_outbox_available
    ON factory_dispatch_outbox (status, available_at);

CREATE TABLE factory_worker_run (
    worker_run_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    build_session_id VARCHAR(128) NOT NULL,
    work_order_id VARCHAR(128) NOT NULL,
    attempt_no INTEGER NOT NULL,
    worker_binding_id VARCHAR(255) NOT NULL,
    worker_adapter_version VARCHAR(128) NOT NULL,
    worker_capability_snapshot_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    active_owner_key VARCHAR(128) NULL,
    action_run_id VARCHAR(64) NULL,
    operation_id VARCHAR(255) NOT NULL,
    attempt_token_hash CHAR(64) NULL,
    external_session_ref VARCHAR(1024) NULL,
    dispatch_outbox_id VARCHAR(128) NULL,
    started_at TIMESTAMP WITH TIME ZONE NULL,
    deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
    heartbeat_at TIMESTAMP WITH TIME ZONE NULL,
    cancel_requested_at TIMESTAMP WITH TIME ZONE NULL,
    completed_at TIMESTAMP WITH TIME ZONE NULL,
    result_artifact_manifest_ref VARCHAR(1024) NULL,
    result_hash CHAR(64) NULL,
    code VARCHAR(128) NULL,
    message VARCHAR(2048) NULL,
    retry_disposition VARCHAR(32) NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_factory_worker_run_tenant_id
        UNIQUE (tenant_id, worker_run_id),
    CONSTRAINT uq_factory_worker_run_attempt
        UNIQUE (tenant_id, work_order_id, attempt_no),
    CONSTRAINT uq_factory_worker_run_active_owner
        UNIQUE (tenant_id, active_owner_key),
    CONSTRAINT uq_factory_worker_run_operation
        UNIQUE (tenant_id, operation_id),
    CONSTRAINT fk_factory_worker_run_build_session
        FOREIGN KEY (tenant_id, build_session_id)
        REFERENCES factory_build_session (tenant_id, build_session_id),
    CONSTRAINT fk_factory_worker_run_work_order
        FOREIGN KEY (tenant_id, build_session_id, work_order_id)
        REFERENCES factory_work_order (tenant_id, build_session_id, work_order_id),
    CONSTRAINT ck_factory_worker_run_active_owner
        CHECK (
            (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'MANUAL_REVIEW', 'TIMED_OUT')
                AND active_owner_key IS NULL)
            OR
            (status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'MANUAL_REVIEW', 'TIMED_OUT')
                AND active_owner_key IS NOT NULL
                AND active_owner_key = work_order_id)
        ),
    CONSTRAINT ck_factory_worker_run_dispatch_ownership
        CHECK (
            (status = 'REQUESTED'
                AND action_run_id IS NULL
                AND attempt_token_hash IS NULL
                AND dispatch_outbox_id IS NULL
                AND started_at IS NULL)
            OR
            (status = 'DISPATCHING'
                AND action_run_id IS NOT NULL
                AND attempt_token_hash IS NOT NULL
                AND dispatch_outbox_id IS NOT NULL
                AND started_at IS NOT NULL)
            OR
            status NOT IN ('REQUESTED', 'DISPATCHING')
        ),
    CONSTRAINT ck_factory_worker_run_attempt
        CHECK (attempt_no > 0),
    CONSTRAINT ck_factory_worker_run_version
        CHECK (version >= 0)
);

CREATE INDEX idx_factory_worker_run_session_status
    ON factory_worker_run (tenant_id, build_session_id, status);

CREATE TABLE factory_decision_point (
    decision_point_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    build_session_id VARCHAR(128) NOT NULL,
    type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    subject_type VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    subject_version BIGINT NOT NULL,
    subject_hash CHAR(64) NOT NULL,
    question_artifact_ref VARCHAR(1024) NOT NULL,
    options_schema_id VARCHAR(255) NOT NULL,
    required_permissions_json TEXT NOT NULL,
    minimum_approvers INTEGER NOT NULL,
    policy_snapshot_ref VARCHAR(1024) NOT NULL,
    opened_at TIMESTAMP WITH TIME ZONE NOT NULL,
    due_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE NULL,
    terminal_decision_id VARCHAR(128) NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uq_factory_decision_point_tenant_id
        UNIQUE (tenant_id, decision_point_id),
    CONSTRAINT uq_factory_decision_point_subject
        UNIQUE (tenant_id, decision_point_id, subject_hash),
    CONSTRAINT fk_factory_decision_point_build_session
        FOREIGN KEY (tenant_id, build_session_id)
        REFERENCES factory_build_session (tenant_id, build_session_id),
    CONSTRAINT ck_factory_decision_point_subject_version
        CHECK (subject_version >= 0),
    CONSTRAINT ck_factory_decision_point_approvers
        CHECK (minimum_approvers > 0),
    CONSTRAINT ck_factory_decision_point_version
        CHECK (version >= 0)
);

CREATE INDEX idx_factory_decision_point_session_status
    ON factory_decision_point (tenant_id, build_session_id, status);

CREATE TABLE factory_decision (
    decision_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    decision_point_id VARCHAR(128) NOT NULL,
    request_idempotency_key VARCHAR(255) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    selected_option VARCHAR(255) NULL,
    reason VARCHAR(2048) NULL,
    decided_by VARCHAR(128) NOT NULL,
    decider_authority_snapshot_ref VARCHAR(1024) NOT NULL,
    subject_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_factory_decision_tenant_id
        UNIQUE (tenant_id, decision_id),
    CONSTRAINT uq_factory_decision_request
        UNIQUE (tenant_id, decision_point_id, request_idempotency_key),
    CONSTRAINT uq_factory_decision_terminal_reference
        UNIQUE (tenant_id, decision_point_id, subject_hash, decision_id),
    CONSTRAINT fk_factory_decision_subject
        FOREIGN KEY (tenant_id, decision_point_id, subject_hash)
        REFERENCES factory_decision_point (tenant_id, decision_point_id, subject_hash)
);

CREATE INDEX idx_factory_decision_point_created
    ON factory_decision (tenant_id, decision_point_id, created_at);

ALTER TABLE factory_decision_point
    ADD CONSTRAINT fk_factory_decision_point_terminal_decision
    FOREIGN KEY (tenant_id, decision_point_id, subject_hash, terminal_decision_id)
    REFERENCES factory_decision (tenant_id, decision_point_id, subject_hash, decision_id);
