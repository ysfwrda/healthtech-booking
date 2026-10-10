-- The notification table as an earlier version of the service created it (Hibernate ddl-auto: update):
-- no event_id, and type limited to the two enum values of that time.
create table notification (
    id uuid not null,
    appointment_id uuid,
    created_at timestamp(6),
    doctor_id uuid,
    message varchar(255),
    patient_id uuid,
    type varchar(255) check (type in ('APPOINTMENT_BOOKED', 'APPOINTMENT_CANCELLED')),
    primary key (id)
);
