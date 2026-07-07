package com.healthtech.appointment.repository;

import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    List<Appointment> findByDoctorIdAndDateTimeGreaterThanEqualAndDateTimeLessThanAndStatusNot(
            UUID doctorId, LocalDateTime startOfDayInclusive, LocalDateTime endOfDayExclusive, AppointmentStatus excludedStatus);

    List<Appointment> findByPatientIdOrderByDateTimeAsc(UUID patientId);
}