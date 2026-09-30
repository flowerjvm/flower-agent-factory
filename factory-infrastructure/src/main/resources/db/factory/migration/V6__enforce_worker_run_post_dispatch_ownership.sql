ALTER TABLE factory_worker_run
    ADD CONSTRAINT ck_factory_worker_run_post_dispatch_ownership
    CHECK (
        (status = 'REQUESTED'
            AND action_run_id IS NULL
            AND attempt_token_hash IS NULL
            AND dispatch_outbox_id IS NULL
            AND started_at IS NULL)
        OR
        (status IN ('CANCEL_REQUESTED', 'CANCELLED')
            AND (
                (action_run_id IS NULL
                    AND attempt_token_hash IS NULL
                    AND dispatch_outbox_id IS NULL
                    AND started_at IS NULL)
                OR
                (action_run_id IS NOT NULL
                    AND attempt_token_hash IS NOT NULL
                    AND dispatch_outbox_id IS NOT NULL
                    AND started_at IS NOT NULL)
            ))
        OR
        (status NOT IN ('REQUESTED', 'CANCEL_REQUESTED', 'CANCELLED')
            AND action_run_id IS NOT NULL
            AND attempt_token_hash IS NOT NULL
            AND dispatch_outbox_id IS NOT NULL
            AND started_at IS NOT NULL)
    );

ALTER TABLE factory_worker_run
    ADD CONSTRAINT ck_factory_worker_run_terminal_result
    CHECK (
        (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'MANUAL_REVIEW', 'TIMED_OUT')
            AND completed_at IS NOT NULL
            AND code IS NOT NULL
            AND message IS NOT NULL
            AND retry_disposition IS NOT NULL)
        OR
        (status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'MANUAL_REVIEW', 'TIMED_OUT')
            AND completed_at IS NULL)
    );
