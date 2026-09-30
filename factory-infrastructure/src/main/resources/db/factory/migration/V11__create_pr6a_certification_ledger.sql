-- Before V11 there was no Certification ledger that could own a historical
-- current_certification_id. Do not silently clear or bless such an orphan. The
-- portable cast fails before any V11 schema mutation on both H2 and PostgreSQL.
SELECT CAST(
    CASE
        WHEN COUNT(*) = 0 THEN '0'
        ELSE 'V11_ORPHAN_CURRENT_CERTIFICATION_REQUIRES_OPERATOR_RESOLUTION'
    END AS INTEGER)
FROM factory_build_session
WHERE current_certification_id IS NOT NULL;

ALTER TABLE factory_build_session
    ADD CONSTRAINT uq_factory_build_session_product_line
    UNIQUE (tenant_id, build_session_id, product_line_id);

ALTER TABLE factory_verification_run
    ADD CONSTRAINT uq_factory_verification_run_certification_lock
    UNIQUE (
        tenant_id, build_session_id, verification_run_id, candidate_id,
        candidate_hash, toolchain_lock_hash, gate_profile, fixture_set_hash,
        result_manifest_ref, result_manifest_hash);

ALTER TABLE action_run
    ADD CONSTRAINT uq_factory_action_run_tenant_id
    UNIQUE (tenant_id, run_id);

ALTER TABLE factory_artifact
    ADD CONSTRAINT uq_factory_artifact_exact_lock
    UNIQUE (tenant_id, artifact_ref, content_hash);

CREATE TABLE factory_certification (
    certification_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    product_line_id VARCHAR(128) NOT NULL,
    artifact_type VARCHAR(64) NOT NULL,
    build_session_id VARCHAR(128) NOT NULL,
    generation_work_order_id VARCHAR(128) NOT NULL,
    candidate_id VARCHAR(128) NOT NULL,
    candidate_hash CHAR(64) NOT NULL,
    source_manifest_ref VARCHAR(1024) NOT NULL,
    source_manifest_hash CHAR(64) NOT NULL,
    dependency_lock_ref VARCHAR(1024) NOT NULL,
    dependency_lock_hash CHAR(64) NOT NULL,
    toolchain_lock_ref VARCHAR(1024) NOT NULL,
    toolchain_lock_hash CHAR(64) NOT NULL,
    generation_input_manifest_ref VARCHAR(1024) NOT NULL,
    generation_input_manifest_hash CHAR(64) NOT NULL,
    product_contract_bundle_ref VARCHAR(1024) NOT NULL,
    product_contract_bundle_hash CHAR(64) NOT NULL,
    api_signature_index_ref VARCHAR(1024) NOT NULL,
    api_signature_index_hash CHAR(64) NOT NULL,
    source_lock_algorithm_id VARCHAR(128) NOT NULL,
    gate_profile VARCHAR(128) NOT NULL,
    verification_run_id VARCHAR(128) NOT NULL,
    verification_action_run_id VARCHAR(64) NOT NULL,
    verification_result_manifest_ref VARCHAR(1024) NOT NULL,
    verification_result_manifest_hash CHAR(64) NOT NULL,
    verification_fixture_set_hash CHAR(64) NOT NULL,
    policy_snapshot_ref VARCHAR(1024) NOT NULL,
    policy_snapshot_hash CHAR(64) NOT NULL,
    compatibility_descriptor_ref VARCHAR(1024) NOT NULL,
    compatibility_descriptor_hash CHAR(64) NOT NULL,
    certification_profile VARCHAR(128) NOT NULL,
    factory_version VARCHAR(128) NOT NULL,
    flower_version VARCHAR(128) NOT NULL,
    action_runtime_version VARCHAR(128) NOT NULL,
    input_lock_json TEXT NOT NULL,
    input_lock_manifest_ref VARCHAR(1024) NOT NULL,
    input_lock_manifest_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    active_key VARCHAR(1024) NULL,
    certification_manifest_ref VARCHAR(1024) NULL,
    certification_manifest_hash CHAR(64) NULL,
    certification_evidence_ref VARCHAR(1024) NULL,
    certification_evidence_hash CHAR(64) NULL,
    action_run_id VARCHAR(64) NULL,
    stable_code VARCHAR(128) NULL,
    issued_at TIMESTAMP WITH TIME ZONE NULL,
    expires_at TIMESTAMP WITH TIME ZONE NULL,
    revoked_at TIMESTAMP WITH TIME ZONE NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_factory_certification_tenant_id
        UNIQUE (tenant_id, certification_id),
    CONSTRAINT uq_factory_certification_session_id
        UNIQUE (tenant_id, build_session_id, certification_id),
    CONSTRAINT uq_factory_certification_active
        UNIQUE (tenant_id, active_key),
    CONSTRAINT fk_factory_certification_build_session
        FOREIGN KEY (tenant_id, build_session_id, product_line_id)
        REFERENCES factory_build_session (tenant_id, build_session_id, product_line_id),
    CONSTRAINT fk_factory_certification_work_order
        FOREIGN KEY (tenant_id, build_session_id, generation_work_order_id)
        REFERENCES factory_work_order (tenant_id, build_session_id, work_order_id),
    CONSTRAINT fk_factory_certification_candidate
        FOREIGN KEY (
            tenant_id, build_session_id, candidate_id, candidate_hash, toolchain_lock_hash)
        REFERENCES factory_candidate_version (
            tenant_id, build_session_id, candidate_id, source_hash, toolchain_lock_hash),
    CONSTRAINT fk_factory_certification_verification
        FOREIGN KEY (
            tenant_id, build_session_id, verification_run_id, candidate_id,
            candidate_hash, toolchain_lock_hash, gate_profile,
            verification_fixture_set_hash, verification_result_manifest_ref,
            verification_result_manifest_hash)
        REFERENCES factory_verification_run (
            tenant_id, build_session_id, verification_run_id, candidate_id,
            candidate_hash, toolchain_lock_hash, gate_profile, fixture_set_hash,
            result_manifest_ref, result_manifest_hash),
    CONSTRAINT fk_factory_certification_verification_action
        FOREIGN KEY (tenant_id, verification_action_run_id)
        REFERENCES action_run (tenant_id, run_id),
    CONSTRAINT fk_factory_certification_action
        FOREIGN KEY (tenant_id, action_run_id)
        REFERENCES action_run (tenant_id, run_id),
    CONSTRAINT fk_factory_certification_source_artifact
        FOREIGN KEY (tenant_id, source_manifest_ref, source_manifest_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_certification_dependency_artifact
        FOREIGN KEY (tenant_id, dependency_lock_ref, dependency_lock_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_certification_toolchain_artifact
        FOREIGN KEY (tenant_id, toolchain_lock_ref, toolchain_lock_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_certification_generation_input_artifact
        FOREIGN KEY (tenant_id, generation_input_manifest_ref, generation_input_manifest_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_certification_product_contract_artifact
        FOREIGN KEY (tenant_id, product_contract_bundle_ref, product_contract_bundle_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_certification_api_signature_artifact
        FOREIGN KEY (tenant_id, api_signature_index_ref, api_signature_index_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_certification_verification_result_artifact
        FOREIGN KEY (
            tenant_id, verification_result_manifest_ref, verification_result_manifest_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_certification_policy_artifact
        FOREIGN KEY (tenant_id, policy_snapshot_ref, policy_snapshot_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_certification_compatibility_artifact
        FOREIGN KEY (
            tenant_id, compatibility_descriptor_ref, compatibility_descriptor_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_certification_input_lock_artifact
        FOREIGN KEY (tenant_id, input_lock_manifest_ref, input_lock_manifest_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_certification_manifest_artifact
        FOREIGN KEY (
            tenant_id, certification_manifest_ref, certification_manifest_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT fk_factory_certification_evidence_artifact
        FOREIGN KEY (
            tenant_id, certification_evidence_ref, certification_evidence_hash)
        REFERENCES factory_artifact (tenant_id, artifact_ref, content_hash),
    CONSTRAINT ck_factory_certification_type
        CHECK (product_line_id = 'agent-pack' AND artifact_type = 'AGENT_PACK'),
    CONSTRAINT ck_factory_certification_hashes
        CHECK (
            CHAR_LENGTH(candidate_hash) = 64
            AND CHAR_LENGTH(source_manifest_hash) = 64
            AND CHAR_LENGTH(dependency_lock_hash) = 64
            AND CHAR_LENGTH(toolchain_lock_hash) = 64
            AND CHAR_LENGTH(generation_input_manifest_hash) = 64
            AND CHAR_LENGTH(product_contract_bundle_hash) = 64
            AND CHAR_LENGTH(api_signature_index_hash) = 64
            AND CHAR_LENGTH(verification_result_manifest_hash) = 64
            AND CHAR_LENGTH(verification_fixture_set_hash) = 64
            AND CHAR_LENGTH(policy_snapshot_hash) = 64
            AND CHAR_LENGTH(compatibility_descriptor_hash) = 64
            AND CHAR_LENGTH(input_lock_manifest_hash) = 64
            AND (certification_manifest_hash IS NULL
                OR CHAR_LENGTH(certification_manifest_hash) = 64)
            AND (certification_evidence_hash IS NULL
                OR CHAR_LENGTH(certification_evidence_hash) = 64)),
    CONSTRAINT ck_factory_certification_version_time
        CHECK (
            version >= 0
            AND updated_at >= created_at
            AND (issued_at IS NULL OR issued_at = created_at)
            AND (expires_at IS NULL OR (issued_at IS NOT NULL AND expires_at > issued_at))
            AND (revoked_at IS NULL OR (issued_at IS NOT NULL AND revoked_at >= issued_at))),
    CONSTRAINT ck_factory_certification_lifecycle
        CHECK (
            (status = 'REQUESTED'
                AND active_key IS NOT NULL
                AND certification_manifest_ref IS NULL
                AND certification_manifest_hash IS NULL
                AND certification_evidence_ref IS NULL
                AND certification_evidence_hash IS NULL
                AND action_run_id IS NULL
                AND stable_code IS NULL
                AND issued_at IS NULL
                AND expires_at IS NULL
                AND revoked_at IS NULL)
            OR
            (status = 'CERTIFIED'
                AND active_key IS NULL
                AND certification_manifest_ref IS NOT NULL
                AND certification_manifest_hash IS NOT NULL
                AND certification_evidence_ref IS NOT NULL
                AND certification_evidence_hash IS NOT NULL
                AND action_run_id IS NOT NULL
                AND stable_code IS NULL
                AND issued_at IS NOT NULL
                AND revoked_at IS NULL)
            OR
            (status = 'NOT_CERTIFIED'
                AND active_key IS NULL
                AND certification_manifest_ref IS NULL
                AND certification_manifest_hash IS NULL
                AND certification_evidence_ref IS NULL
                AND certification_evidence_hash IS NULL
                AND action_run_id IS NULL
                AND stable_code IS NOT NULL
                AND issued_at IS NULL
                AND expires_at IS NULL
                AND revoked_at IS NULL)
            OR
            (status = 'REVOKED'
                AND active_key IS NULL
                AND certification_manifest_ref IS NOT NULL
                AND certification_manifest_hash IS NOT NULL
                AND certification_evidence_ref IS NOT NULL
                AND certification_evidence_hash IS NOT NULL
                AND action_run_id IS NOT NULL
                AND stable_code IS NOT NULL
                AND issued_at IS NOT NULL
                AND revoked_at IS NOT NULL)
        )
);

CREATE INDEX idx_factory_certification_candidate_status
    ON factory_certification (tenant_id, candidate_id, candidate_hash, status);

ALTER TABLE factory_build_session
    ADD CONSTRAINT fk_factory_build_session_current_certification
    FOREIGN KEY (tenant_id, build_session_id, current_certification_id)
    REFERENCES factory_certification (tenant_id, build_session_id, certification_id);
