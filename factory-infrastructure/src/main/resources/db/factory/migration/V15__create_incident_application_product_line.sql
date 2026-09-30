CREATE TABLE factory_incident_application (
    tenant_id VARCHAR(128) NOT NULL,
    build_session_id VARCHAR(128) NOT NULL,
    product_line_id VARCHAR(128) NOT NULL,
    request_key VARCHAR(255) NOT NULL,
    component_certification_id VARCHAR(128) NOT NULL,
    component_candidate_hash CHAR(64) NOT NULL,
    component_manifest_ref VARCHAR(1024) NOT NULL,
    component_manifest_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    product_json TEXT NOT NULL,
    product_hash CHAR(64) NOT NULL,
    certification_ref VARCHAR(1024) NULL,
    certification_hash CHAR(64) NULL,
    release_ref VARCHAR(1024) NULL,
    release_hash CHAR(64) NULL,
    release_action_run_id VARCHAR(64) NULL,
    version BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, build_session_id),
    CONSTRAINT uq_incident_application_request UNIQUE (tenant_id, request_key),
    CONSTRAINT fk_incident_application_session FOREIGN KEY (tenant_id, build_session_id, product_line_id)
        REFERENCES factory_build_session (tenant_id, build_session_id, product_line_id),
    CONSTRAINT fk_incident_application_component FOREIGN KEY (tenant_id, component_certification_id,
        component_candidate_hash, component_manifest_ref, component_manifest_hash)
        REFERENCES factory_certification (tenant_id, certification_id, candidate_hash, certification_manifest_ref, certification_manifest_hash),
    CONSTRAINT fk_incident_application_certificate FOREIGN KEY (tenant_id, certification_ref, certification_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_incident_application_release FOREIGN KEY (tenant_id, release_ref, release_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_incident_application_release_action FOREIGN KEY (tenant_id, release_action_run_id)
        REFERENCES action_run (tenant_id, run_id),
    CONSTRAINT ck_incident_application_line CHECK (product_line_id = 'incident-application'),
    CONSTRAINT ck_incident_application_version CHECK (version >= 0),
    CONSTRAINT ck_incident_application_status CHECK (status IN ('ACCEPTED','BUILDING','BUILT','VERIFYING','REVIEW','RELEASING','RELEASED','FAILED','MANUAL_REVIEW')),
    CONSTRAINT ck_incident_application_release CHECK ((status = 'RELEASED' AND certification_ref IS NOT NULL
        AND certification_hash IS NOT NULL AND release_ref IS NOT NULL AND release_hash IS NOT NULL AND release_action_run_id IS NOT NULL)
        OR (status <> 'RELEASED' AND certification_ref IS NULL AND certification_hash IS NULL AND release_ref IS NULL AND release_hash IS NULL))
);

CREATE TABLE factory_incident_application_intent (
    operation_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    build_session_id VARCHAR(128) NOT NULL,
    stage VARCHAR(16) NOT NULL,
    subject_version BIGINT NOT NULL,
    action_run_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    intent_json TEXT NOT NULL,
    intent_hash CHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_incident_application_stage UNIQUE (tenant_id, build_session_id, stage, subject_version),
    CONSTRAINT uq_incident_application_action UNIQUE (action_run_id),
    CONSTRAINT fk_incident_application_intent_product FOREIGN KEY (tenant_id, build_session_id)
        REFERENCES factory_incident_application (tenant_id, build_session_id),
    CONSTRAINT fk_incident_application_intent_action FOREIGN KEY (tenant_id, action_run_id)
        REFERENCES action_run (tenant_id, run_id),
    CONSTRAINT ck_incident_application_intent_version CHECK (version >= 0 AND subject_version >= 0),
    CONSTRAINT ck_incident_application_intent_stage CHECK (stage IN ('BUILD','VERIFY','RELEASE')),
    CONSTRAINT ck_incident_application_intent_status CHECK (status IN ('PENDING','RUNNING','EFFECT_COMMITTED','COMPLETED','FAILED','MANUAL_REVIEW','CANCELLED'))
);
CREATE INDEX ix_incident_application_active ON factory_incident_application(status,updated_at);
CREATE INDEX ix_incident_application_pending ON factory_incident_application_intent(status,created_at);
