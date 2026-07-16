-- cleanup-duplicate-opening-hours.sql
--
-- One-time cleanup for identity-distinct duplicate rows in doctor-service's
-- doctor_opening_hours collection table (Doctor.openingHours, an
-- @ElementCollection with no @CollectionTable override, so the table/column
-- names below come from Spring Boot's default Hibernate naming strategy:
-- table "doctor_opening_hours", columns doctor_id/day_of_week/start_time/end_time).
--
-- Must run AFTER OpeningHours gained equals()/hashCode() (see
-- doctor-service/src/main/java/com/healthtech/doctor/domain/OpeningHours.java).
-- Before that fix, Set<OpeningHours> fell back to identity equality, so any
-- duplicate row that ever entered this table couldn't be deduped in memory
-- and would keep round-tripping intact on every profile update. Running this
-- cleanup first would just let the next Hibernate delete-all-then-reinsert of
-- the collection reintroduce the same duplicates.
--
-- Idempotent and safe to re-run: keeps one arbitrary row per
-- (doctor_id, day_of_week, start_time, end_time) and deletes the rest. A
-- second run finds nothing left to delete.
--
-- Usage: psql "$DOCTOR_SERVICE_DB_URL" -f scripts/cleanup-duplicate-opening-hours.sql

-- Inspect before cleaning (optional):
-- SELECT doctor_id, day_of_week, start_time, end_time, COUNT(*)
-- FROM doctor_opening_hours
-- GROUP BY doctor_id, day_of_week, start_time, end_time
-- HAVING COUNT(*) > 1;

DELETE FROM doctor_opening_hours a
USING doctor_opening_hours b
WHERE a.ctid < b.ctid
  AND a.doctor_id = b.doctor_id
  AND a.day_of_week = b.day_of_week
  AND a.start_time = b.start_time
  AND a.end_time = b.end_time;
