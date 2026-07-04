CREATE UNIQUE INDEX IF NOT EXISTS ux_active_appointment
    ON appointments (doctor_id, date_time)
    WHERE status <> 'CANCELLED';