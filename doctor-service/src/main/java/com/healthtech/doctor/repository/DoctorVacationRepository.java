package com.healthtech.doctor.repository;

import com.healthtech.doctor.domain.DoctorVacation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DoctorVacationRepository extends JpaRepository<DoctorVacation, UUID> {

    // Vacations that have not ended before the given day.
    List<DoctorVacation> findByDoctorIdAndEndDateGreaterThanEqualOrderByStartDateAsc(UUID doctorId, LocalDate today);

    Optional<DoctorVacation> findByIdAndDoctorId(UUID id, UUID doctorId);
}
