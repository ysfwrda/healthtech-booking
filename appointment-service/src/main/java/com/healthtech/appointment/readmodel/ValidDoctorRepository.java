package com.healthtech.appointment.readmodel;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ValidDoctorRepository extends JpaRepository<ValidDoctor, UUID> {

    // Availability writes and doctor.registered take the row exclusively. The wait is capped at 3s via the JDBC
    // query timeout, because the Hibernate PostgreSQL dialect ignores jakarta.persistence.lock.timeout. The
    // collections (hours, vacations) are loaded by further selects after the lock is granted, not in this statement.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "3000"))
    @Query("select d from ValidDoctor d where d.doctorId = :id")
    Optional<ValidDoctor> findByIdForUpdate(@Param("id") UUID id);

    // Booking and time changes read the doctor shared, so they wait for an availability write in progress.
    @Lock(LockModeType.PESSIMISTIC_READ)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "3000"))
    @Query("select d from ValidDoctor d where d.doctorId = :id")
    Optional<ValidDoctor> findByIdForShare(@Param("id") UUID id);
}
