ALTER TABLE factory_build_session
    ADD COLUMN product_line_id VARCHAR(128) DEFAULT 'agent-pack' NOT NULL;

ALTER TABLE factory_build_session
    ALTER COLUMN product_line_id DROP DEFAULT;
