-- Append-only one-shot renewal; original order, deadlines, subject and production artifacts remain immutable.
CREATE TABLE factory_incident_review_renewal (
    tenant_id VARCHAR(128) NOT NULL,
    build_session_id VARCHAR(128) NOT NULL,
    previous_decision_point_id VARCHAR(128) NOT NULL,
    decision_point_id VARCHAR(128) NOT NULL,
    action_run_id VARCHAR(64) NOT NULL,
    renewal_json TEXT NOT NULL,
    renewal_hash CHAR(64) NOT NULL,
    evidence_ref VARCHAR(1024) NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, build_session_id),
    UNIQUE (tenant_id, decision_point_id),
    UNIQUE (action_run_id),
    FOREIGN KEY (tenant_id, build_session_id) REFERENCES factory_incident_application (tenant_id, build_session_id),
    FOREIGN KEY (tenant_id, previous_decision_point_id) REFERENCES factory_decision_point (tenant_id, decision_point_id),
    FOREIGN KEY (tenant_id, decision_point_id) REFERENCES factory_decision_point (tenant_id, decision_point_id),
    FOREIGN KEY (tenant_id, action_run_id) REFERENCES action_run (tenant_id, run_id),
    FOREIGN KEY (tenant_id, evidence_ref, evidence_hash) REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CHECK (previous_decision_point_id <> decision_point_id)
);
