package com.healthtech.appointment.repository;

import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, UUID>, JpaSpecificationExecutor<Appointment> {

    List<Appointment> findByDoctorIdAndDateTimeGreaterThanEqualAndDateTimeLessThanAndStatusNot(
            UUID doctorId, LocalDateTime startOfDayInclusive, LocalDateTime endOfDayExclusive, AppointmentStatus excludedStatus);

    List<Appointment> findByPatientIdOrderByDateTimeAsc(UUID patientId);

    // Ids only: the availability re-evaluation locks and reads each appointment in its own transaction.
    @Query("select a.id from Appointment a where a.doctorId = :doctorId and a.status <> :excluded and a.dateTime >= :from order by a.dateTime")
    List<UUID> findIdsByDoctorFromExcludingStatus(@Param("doctorId") UUID doctorId, @Param("from") LocalDateTime from,
                                                  @Param("excluded") AppointmentStatus excluded);

    // The row lock serializes concurrent cancels. The wait is capped at 3s via the JDBC query timeout,
    // because the Hibernate PostgreSQL dialect ignores jakarta.persistence.lock.timeout.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "3000"))
    @Query("select a from Appointment a where a.id = :id")
    Optional<Appointment> findByIdForUpdate(@Param("id") UUID id);
}