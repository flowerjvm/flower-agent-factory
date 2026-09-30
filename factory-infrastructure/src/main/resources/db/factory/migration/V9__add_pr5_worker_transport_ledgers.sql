-- A pre-PR5 DISPATCHING row has no durable claim purpose.  Guessing whether its
-- lease represented a submit or a status-only reconciliation could duplicate an
-- external effect, so fail before changing the schema and require an operator to
-- reconcile those rows by deterministic operation id.
SELECT CAST(
    CASE
        WHEN COUNT(*) = 0 THEN '0'
        ELSE 'V9_LEGACY_DISPATCHING_OUTBOX_REQUIRES_OPERATOR_RECONCILIATION'
    END AS INTEGER)
FROM factory_dispatch_outbox
WHERE status = 'DISPATCHING';

SELECT CAST(
    CASE
        WHEN COUNT(*) = 0 THEN '0'
        ELSE 'V9_LEGACY_OUTBOX_LIFECYCLE_REQUIRES_OPERATOR_RESOLUTION'
    END AS INTEGER)
FROM factory_dispatch_outbox
WHERE updated_at < created_at
   OR (status = 'PENDING' AND (attempt_count <> 0 OR last_code IS NOT NULL))
   OR (status = 'RETRY_WAIT' AND (attempt_count <= 0 OR last_code IS NULL))
   OR (status IN ('DISPATCHED', 'MANUAL_REVIEW')
       AND (attempt_count <= 0 OR last_code IS NULL))
   OR status NOT IN ('PENDING', 'DISPATCHING', 'DISPATCHED', 'RETRY_WAIT', 'MANUAL_REVIEW');

SELECT CAST(
    CASE
        WHEN COUNT(*) = 0 THEN '0'
        ELSE 'V9_WORKER_DISPATCH_OUTBOX_BINDING_REQUIRES_OPERATOR_RESOLUTION'
    END AS INTEGER)
FROM factory_worker_run wr
LEFT JOIN factory_dispatch_outbox outbound
  ON outbound.tenant_id = wr.tenant_id
 AND outbound.outbox_id = wr.dispatch_outbox_id
WHERE wr.dispatch_outbox_id IS NOT NULL
  AND (
      outbound.outbox_id IS NULL
      OR outbound.operation_type <> 'WORKER_DISPATCH'
      OR outbound.aggregate_type <> 'WORKER_RUN'
      OR outbound.aggregate_id <> wr.worker_run_id
      OR outbound.operation_id <> wr.operation_id
  );

ALTER TABLE factory_dispatch_outbox
    ADD COLUMN claim_token VARCHAR(128) NULL;

ALTER TABLE factory_dispatch_outbox
    ADD COLUMN claim_purpose VARCHAR(32) NULL;

ALTER TABLE factory_dispatch_outbox
    ADD COLUMN claimed_at TIMESTAMP WITH TIME ZONE NULL;

ALTER TABLE factory_dispatch_outbox
    ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE NULL;

ALTER TABLE factory_dispatch_outbox
    DROP CONSTRAINT uq_factory_dispatch_outbox_operation;

-- Dispatch and cancellation are separate commands.  Their deterministic
-- operation ids may intentionally share the same aggregate-derived value.
ALTER TABLE factory_dispatch_outbox
    ADD CONSTRAINT uq_factory_dispatch_outbox_operation
    UNIQUE (tenant_id, operation_type, operation_id);

ALTER TABLE factory_dispatch_outbox
    ADD CONSTRAINT uq_factory_dispatch_outbox_worker_binding
    UNIQUE (tenant_id, outbox_id, aggregate_id, operation_id);

ALTER TABLE factory_dispatch_outbox
    ADD CONSTRAINT ck_factory_dispatch_outbox_worker_aggregate
    CHECK (
        operation_type NOT IN ('WORKER_DISPATCH', 'WORKER_CANCEL')
        OR aggregate_type = 'WORKER_RUN'
    );

ALTER TABLE factory_dispatch_outbox
    ADD CONSTRAINT ck_factory_dispatch_outbox_operation_terminal
    CHECK (
        (status NOT IN ('CONFIRMED', 'SUPERSEDED') OR operation_type = 'WORKER_CANCEL')
        AND (operation_type <> 'WORKER_CANCEL' OR status <> 'DISPATCHED')
    );

ALTER TABLE factory_dispatch_outbox
    ADD CONSTRAINT ck_factory_dispatch_outbox_lifecycle
    CHECK (
        (status = 'PENDING'
            AND claim_token IS NULL
            AND claim_purpose IS NULL
            AND claimed_at IS NULL
            AND lease_until IS NULL
            AND attempt_count = 0
            AND last_code IS NULL)
        OR
        (status = 'RETRY_WAIT'
            AND claim_token IS NULL
            AND claim_purpose IS NULL
            AND claimed_at IS NULL
            AND lease_until IS NULL
            AND attempt_count > 0
            AND last_code IS NOT NULL)
        OR
        (status = 'DISPATCHING'
            AND claim_token IS NOT NULL
            AND claim_purpose IN ('SUBMIT', 'RECONCILE')
            AND claimed_at IS NOT NULL
            AND lease_until IS NOT NULL
            AND lease_until > claimed_at
            AND attempt_count > 0)
        OR
        (status IN ('DISPATCHED', 'CONFIRMED', 'SUPERSEDED')
            AND claim_token IS NULL
            AND claim_purpose IS NULL
            AND claimed_at IS NULL
            AND lease_until IS NULL
            AND attempt_count > 0
            AND last_code IS NOT NULL)
        OR
        (status = 'MANUAL_REVIEW'
            AND claim_token IS NULL
            AND claim_purpose IS NULL
            AND claimed_at IS NULL
            AND lease_until IS NULL
            AND attempt_count >= 0
            AND last_code IS NOT NULL)
    );

ALTER TABLE factory_dispatch_outbox
    ADD CONSTRAINT ck_factory_dispatch_outbox_time
    CHECK (updated_at >= created_at);

ALTER TABLE factory_worker_run
    ADD CONSTRAINT fk_factory_worker_run_dispatch_outbox
    FOREIGN KEY (tenant_id, dispatch_outbox_id, worker_run_id, operation_id)
    REFERENCES factory_dispatch_outbox (tenant_id, outbox_id, aggregate_id, operation_id);

ALTER TABLE factory_worker_run
    ADD CONSTRAINT uq_factory_worker_run_callback_binding
    UNIQUE (
        tenant_id, worker_run_id, work_order_id, operation_id,
        worker_binding_id, attempt_token_hash
    );

DROP INDEX idx_factory_dispatch_outbox_available;

CREATE INDEX idx_factory_dispatch_outbox_available
    ON factory_dispatch_outbox (status, available_at, created_at, outbox_id);

CREATE INDEX idx_factory_dispatch_outbox_reconcile
    ON factory_dispatch_outbox (status, lease_until, updated_at, outbox_id);

CREATE INDEX idx_factory_worker_run_reconcile_host
    ON factory_worker_run (status, deadline_at, updated_at, tenant_id, worker_run_id);

CREATE INDEX idx_factory_worker_run_reconcile_tenant
    ON factory_worker_run (tenant_id, status, deadline_at, updated_at, worker_run_id);

CREATE INDEX idx_factory_worker_run_active_session
    ON factory_worker_run (tenant_id, build_session_id, status, updated_at, worker_run_id);

-- The inbox is written only after transport authentication establishes tenant
-- authority.  It contains the canonical attempt-token hash, never a raw token,
-- signature, credential, or caller-supplied tenant authority.
CREATE TABLE factory_worker_callback_inbox (
    callback_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    worker_binding_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    work_order_id VARCHAR(128) NOT NULL,
    worker_run_id VARCHAR(128) NOT NULL,
    operation_id VARCHAR(256) NOT NULL,
    attempt_token_hash CHAR(64) NOT NULL,
    payload_artifact_ref VARCHAR(1024) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claim_token VARCHAR(128) NULL,
    lease_until TIMESTAMP WITH TIME ZONE NULL,
    delivery_count INTEGER NOT NULL,
    last_code VARCHAR(128) NULL,
    version BIGINT NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_factory_worker_callback_tenant_id UNIQUE (tenant_id, callback_id),
    CONSTRAINT uq_factory_worker_callback_event
        UNIQUE (tenant_id, worker_binding_id, event_id),
    CONSTRAINT fk_factory_worker_callback_payload
        FOREIGN KEY (tenant_id, payload_artifact_ref)
        REFERENCES factory_artifact (tenant_id, artifact_ref),
    CONSTRAINT fk_factory_worker_callback_worker_binding
        FOREIGN KEY (
            tenant_id, worker_run_id, work_order_id, operation_id,
            worker_binding_id, attempt_token_hash
        ) REFERENCES factory_worker_run (
            tenant_id, worker_run_id, work_order_id, operation_id,
            worker_binding_id, attempt_token_hash
        ),
    CONSTRAINT ck_factory_worker_callback_hashes
        CHECK (CHAR_LENGTH(attempt_token_hash) = 64 AND CHAR_LENGTH(payload_hash) = 64),
    CONSTRAINT ck_factory_worker_callback_version
        CHECK (version >= 0 AND delivery_count > 0),
    CONSTRAINT ck_factory_worker_callback_time
        CHECK (updated_at >= received_at),
    CONSTRAINT ck_factory_worker_callback_lifecycle
        CHECK (
            (status = 'RECEIVED'
                AND claim_token IS NULL
                AND lease_until IS NULL
                AND ((version = 0 AND last_code IS NULL)
                    OR (version > 0 AND last_code IS NOT NULL)))
            OR
            (status = 'PROCESSING'
                AND claim_token IS NOT NULL
                AND lease_until IS NOT NULL
                AND lease_until > updated_at
                AND version > 0)
            OR
            (status IN ('APPLIED', 'SUPERSEDED', 'REJECTED', 'MANUAL_REVIEW')
                AND claim_token IS NULL
                AND lease_until IS NULL
                AND last_code IS NOT NULL
                AND version > 0)
        )
);

CREATE INDEX idx_factory_worker_callback_pending
    ON factory_worker_callback_inbox (status, available_at, received_at, callback_id);

CREATE INDEX idx_factory_worker_callback_expired
    ON factory_worker_callback_inbox (status, lease_until, updated_at, callback_id);

CREATE INDEX idx_factory_worker_callback_worker
    ON factory_worker_callback_inbox (tenant_id, worker_run_id, received_at);

CREATE TABLE factory_worker_callback_audit (
    audit_id VARCHAR(36) PRIMARY KEY,
    trusted_tenant_id VARCHAR(128) NULL,
    worker_binding_id VARCHAR(128) NOT NULL,
    authenticated_principal_ref VARCHAR(256) NULL,
    event_id VARCHAR(128) NULL,
    worker_run_id VARCHAR(128) NULL,
    code VARCHAR(128) NOT NULL,
    accepted BOOLEAN NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_factory_worker_callback_audit_tenant
    ON factory_worker_callback_audit (trusted_tenant_id, observed_at, audit_id);
