ALTER TABLE factory_certification
    ADD CONSTRAINT uq_factory_certification_reference_assembly_lock
    UNIQUE (
        tenant_id, certification_id, candidate_hash,
        certification_manifest_ref, certification_manifest_hash);

ALTER TABLE factory_decision_point
    ADD CONSTRAINT uq_factory_decision_point_reference_assembly_release
    UNIQUE (tenant_id, build_session_id, decision_point_id, subject_hash);

CREATE TABLE factory_reference_assembly (
    reference_assembly_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    build_session_id VARCHAR(128) NOT NULL,
    requirement_ref VARCHAR(1024) NOT NULL,
    requirement_hash CHAR(64) NOT NULL,
    consumer_contract_ref VARCHAR(1024) NOT NULL,
    consumer_contract_hash CHAR(64) NOT NULL,
    host_fixture_ref VARCHAR(1024) NOT NULL,
    host_fixture_hash CHAR(64) NOT NULL,
    policy_snapshot_ref VARCHAR(1024) NOT NULL,
    policy_snapshot_hash CHAR(64) NOT NULL,
    component_certification_id VARCHAR(128) NOT NULL,
    component_candidate_hash CHAR(64) NOT NULL,
    component_certification_manifest_ref VARCHAR(1024) NOT NULL,
    component_certification_manifest_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    assembly_manifest_ref VARCHAR(1024) NULL,
    assembly_manifest_hash CHAR(64) NULL,
    inspection_report_ref VARCHAR(1024) NULL,
    inspection_report_hash CHAR(64) NULL,
    release_manifest_ref VARCHAR(1024) NULL,
    release_manifest_hash CHAR(64) NULL,
    release_decision_point_id VARCHAR(128) NULL,
    release_subject_hash CHAR(64) NULL,
    release_action_run_id VARCHAR(64) NULL,
    stable_code VARCHAR(128) NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_factory_reference_assembly_tenant_id
        UNIQUE (tenant_id, reference_assembly_id),
    CONSTRAINT uq_factory_reference_assembly_build_session
        UNIQUE (tenant_id, build_session_id),
    CONSTRAINT fk_factory_reference_assembly_build_session
        FOREIGN KEY (tenant_id, build_session_id)
        REFERENCES factory_build_session (tenant_id, build_session_id),
    CONSTRAINT fk_factory_reference_assembly_component_certification
        FOREIGN KEY (
            tenant_id, component_certification_id, component_candidate_hash,
            component_certification_manifest_ref, component_certification_manifest_hash)
        REFERENCES factory_certification (
            tenant_id, certification_id, candidate_hash,
            certification_manifest_ref, certification_manifest_hash),
    CONSTRAINT fk_factory_reference_assembly_requirement_artifact
        FOREIGN KEY (tenant_id, requirement_ref, requirement_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_reference_assembly_consumer_contract_artifact
        FOREIGN KEY (tenant_id, consumer_contract_ref, consumer_contract_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_reference_assembly_host_fixture_artifact
        FOREIGN KEY (tenant_id, host_fixture_ref, host_fixture_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_reference_assembly_policy_artifact
        FOREIGN KEY (tenant_id, policy_snapshot_ref, policy_snapshot_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_reference_assembly_component_manifest_artifact
        FOREIGN KEY (
            tenant_id,
            component_certification_manifest_ref,
            component_certification_manifest_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_reference_assembly_assembly_manifest_artifact
        FOREIGN KEY (tenant_id, assembly_manifest_ref, assembly_manifest_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_reference_assembly_inspection_report_artifact
        FOREIGN KEY (tenant_id, inspection_report_ref, inspection_report_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_reference_assembly_release_manifest_artifact
        FOREIGN KEY (tenant_id, release_manifest_ref, release_manifest_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_reference_assembly_release_decision_point
        FOREIGN KEY (
            tenant_id, build_session_id, release_decision_point_id, release_subject_hash)
        REFERENCES factory_decision_point (
            tenant_id, build_session_id, decision_point_id, subject_hash),
    CONSTRAINT fk_factory_reference_assembly_release_action_run
        FOREIGN KEY (tenant_id, release_action_run_id)
        REFERENCES action_run (tenant_id, run_id),
    CONSTRAINT ck_factory_reference_assembly_hashes
        CHECK (
            CHAR_LENGTH(requirement_hash) = 64
            AND CHAR_LENGTH(consumer_contract_hash) = 64
            AND CHAR_LENGTH(host_fixture_hash) = 64
            AND CHAR_LENGTH(policy_snapshot_hash) = 64
            AND CHAR_LENGTH(component_candidate_hash) = 64
            AND CHAR_LENGTH(component_certification_manifest_hash) = 64
            AND (assembly_manifest_hash IS NULL
                OR CHAR_LENGTH(assembly_manifest_hash) = 64)
            AND (inspection_report_hash IS NULL
                OR CHAR_LENGTH(inspection_report_hash) = 64)
            AND (release_manifest_hash IS NULL
                OR CHAR_LENGTH(release_manifest_hash) = 64)
            AND (release_subject_hash IS NULL
                OR CHAR_LENGTH(release_subject_hash) = 64)),
    CONSTRAINT ck_factory_reference_assembly_lock_pairs
        CHECK (
            (assembly_manifest_ref IS NULL) = (assembly_manifest_hash IS NULL)
            AND (inspection_report_ref IS NULL) = (inspection_report_hash IS NULL)
            AND (release_manifest_ref IS NULL) = (release_manifest_hash IS NULL)),
    CONSTRAINT ck_factory_reference_assembly_version_time
        CHECK (
            version >= 0
            AND updated_at >= created_at),
    CONSTRAINT ck_factory_reference_assembly_lifecycle
        CHECK (
            (status IN ('REQUESTED', 'COMPONENT_RESOLVED')
                AND assembly_manifest_ref IS NULL
                AND inspection_report_ref IS NULL
                AND release_manifest_ref IS NULL
                AND release_decision_point_id IS NULL
                AND release_subject_hash IS NULL
                AND release_action_run_id IS NULL
                AND stable_code IS NULL)
            OR
            (status = 'ASSEMBLED'
                AND assembly_manifest_ref IS NOT NULL
                AND inspection_report_ref IS NULL
                AND release_manifest_ref IS NULL
                AND release_decision_point_id IS NULL
                AND release_subject_hash IS NULL
                AND release_action_run_id IS NULL
                AND stable_code IS NULL)
            OR
            (status = 'INSPECTED'
                AND assembly_manifest_ref IS NOT NULL
                AND inspection_report_ref IS NOT NULL
                AND release_manifest_ref IS NULL
                AND ((release_decision_point_id IS NULL
                        AND release_subject_hash IS NULL
                        AND release_action_run_id IS NULL)
                    OR (release_decision_point_id IS NOT NULL
                        AND release_subject_hash IS NOT NULL))
                AND stable_code IS NULL)
            OR
            (status = 'RELEASED'
                AND assembly_manifest_ref IS NOT NULL
                AND inspection_report_ref IS NOT NULL
                AND release_manifest_ref IS NOT NULL
                AND release_decision_point_id IS NOT NULL
                AND release_subject_hash IS NOT NULL
                AND release_action_run_id IS NOT NULL
                AND stable_code IS NULL)
            OR
            (status = 'REJECTED'
                AND (inspection_report_ref IS NULL OR assembly_manifest_ref IS NOT NULL)
                AND release_manifest_ref IS NULL
                AND ((release_decision_point_id IS NULL
                        AND release_subject_hash IS NULL
                        AND release_action_run_id IS NULL)
                    OR (release_decision_point_id IS NOT NULL
                        AND release_subject_hash IS NOT NULL))
                AND stable_code IS NOT NULL)
        )
);

CREATE INDEX idx_factory_reference_assembly_component_release
    ON factory_reference_assembly (
        tenant_id, component_certification_id, status, created_at, reference_assembly_id);

CREATE INDEX idx_factory_reference_assembly_status
    ON factory_reference_assembly (tenant_id, status, updated_at, reference_assembly_id);
