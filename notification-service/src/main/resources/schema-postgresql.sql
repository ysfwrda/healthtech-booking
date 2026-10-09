-- Hibernate (ddl-auto: update) created notification.type with a CHECK limited to the enum values of that
-- time and never alters it, so a new NotificationType would be rejected on an existing database.
ALTER TABLE notification DROP CONSTRAINT IF EXISTS notification_type_check;

-- Idempotent consumers: one notification per event id (rows from before this column have NULL, which is allowed).
CREATE UNIQUE INDEX IF NOT EXISTS ux_notification_event_id ON notification (event_id);
