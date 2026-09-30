CREATE TABLE action_audit (
    event_id VARCHAR(128) PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    proposal_id VARCHAR(128) NOT NULL,
    action_id VARCHAR(255) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload_json TEXT NOT NULL
);

CREATE INDEX idx_action_audit_tenant_run
    ON action_audit (tenant_id, run_id, occurred_at);

CREATE INDEX idx_action_audit_tenant_action
    ON action_audit (tenant_id, action_id, occurred_at);
