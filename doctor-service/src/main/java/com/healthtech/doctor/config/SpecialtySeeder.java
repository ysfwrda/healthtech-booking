package com.healthtech.doctor.config;

import com.healthtech.doctor.domain.Specialty;
import com.healthtech.doctor.repository.SpecialtyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

// Must run before DoctorSeeder, which resolves specialties by name and fails loudly if
// they don't exist yet. Explicit @Order on both runners (rather than relying on bean
// registration order) makes that dependency deterministic regardless of classpath scanning order.
@Component
@Order(1)
@RequiredArgsConstructor
public class SpecialtySeeder implements CommandLineRunner {
    private final SpecialtyRepository specialtyRepository;

    @Override
    public void run(String... args) {
        List<String> demoSpecialties = List.of(
                "General Practice", "Cardiology", "Dermatology",
                "Pediatrics", "Orthopedics", "Gynecology", "Neurology"
        );

        demoSpecialties.forEach(name -> {
            if (specialtyRepository.findByName(name).isEmpty()) {
                specialtyRepository.save(Specialty.builder().name(name).build());
            }
        });
    }
}
