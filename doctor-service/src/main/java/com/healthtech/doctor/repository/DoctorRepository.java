package com.healthtech.doctor.repository;

import com.healthtech.doctor.domain.Doctor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DoctorRepository extends JpaRepository<Doctor, UUID>, JpaSpecificationExecutor<Doctor> {
    @EntityGraph(attributePaths = {"specialties", "openingHours", "languages"})
    Optional<Doctor> findWithDetailsById(UUID id);

    Optional<Doctor> findByEmail(String email);
}
