CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox (created_at) WHERE published_at IS NULL;
