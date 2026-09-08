CREATE UNIQUE INDEX IF NOT EXISTS ux_active_appointment
    ON appointments (doctor_id, date_time)
    WHERE status <> 'CANCELLED';

CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox (created_at) WHERE published_at IS NULL;