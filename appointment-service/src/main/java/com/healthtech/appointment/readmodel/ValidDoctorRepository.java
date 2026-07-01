package com.healthtech.appointment.readmodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ValidDoctorRepository extends JpaRepository<ValidDoctor, UUID> {
}
