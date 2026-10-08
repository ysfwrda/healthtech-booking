package com.healthtech.appointment.repository;

import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    List<Appointment> findByDoctorIdAndDateTimeGreaterThanEqualAndDateTimeLessThanAndStatusNot(
            UUID doctorId, LocalDateTime startOfDayInclusive, LocalDateTime endOfDayExclusive, AppointmentStatus excludedStatus);

    List<Appointment> findByPatientIdOrderByDateTimeAsc(UUID patientId);

    // Row lock for cancel: concurrent cancels of one appointment queue up, so the second sees CANCELLED
    // and takes the idempotent no-op path instead of writing a duplicate appointment.cancelled event.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appointment a where a.id = :id")
    Optional<Appointment> findByIdForUpdate(@Param("id") UUID id);
}