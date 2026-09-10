package com.healthtech.patient.repository;

import com.healthtech.patient.domain.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientRepository extends JpaRepository<Patient, UUID> {
    Optional<Patient> findByUsername(String username);

    // REQUIRES_NEW: called from AuthService.register's DataIntegrityViolationException handler,
    // after a failed saveAndFlush has already aborted the enclosing @Transactional's connection
    // at the Postgres level (any error poisons the rest of that transaction). Running on a fresh
    // connection is what lets these checks succeed instead of failing with "current transaction
    // is aborted".
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    boolean existsByUsername(String username);

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    boolean existsByEmail(String email);
}
