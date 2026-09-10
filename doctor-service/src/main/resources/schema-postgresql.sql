CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox (created_at) WHERE published_at IS NULL;

-- Supports OutboxPruningJob's delete predicate, the same way idx_outbox_pending supports the
-- relay's claim predicate: without it, the daily prune sequentially scans the whole table.
CREATE INDEX IF NOT EXISTS idx_outbox_published
    ON outbox (published_at) WHERE published_at IS NOT NULL;
