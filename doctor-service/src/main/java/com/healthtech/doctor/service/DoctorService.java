package com.healthtech.doctor.service;

import com.healthtech.doctor.domain.Doctor;
import com.healthtech.doctor.domain.Language;
import com.healthtech.doctor.domain.Specialty;
import com.healthtech.doctor.dto.CreateDoctorRequest;
import com.healthtech.doctor.dto.DoctorResponse;
import com.healthtech.doctor.dto.DoctorSummaryResponse;
import com.healthtech.doctor.event.DoctorRegistered;
import com.healthtech.doctor.exception.DoctorNotFoundException;
import com.healthtech.doctor.exception.EmailAlreadyExistsException;
import com.healthtech.doctor.exception.SpecialtyNotFoundException;
import com.healthtech.doctor.mapper.DoctorMapper;
import com.healthtech.doctor.repository.DoctorRepository;
import com.healthtech.doctor.repository.DoctorSpecifications;
import com.healthtech.doctor.repository.SpecialtyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DoctorService {

    private final DoctorRepository doctorRepository;
    private final SpecialtyRepository specialtyRepository;
    private final DoctorMapper doctorMapper;
    private final KafkaTemplate<String, DoctorRegistered> kafkaTemplate;

    @Transactional
    public DoctorResponse createDoctor(CreateDoctorRequest request) {
        Doctor doctor = doctorMapper.toEntity(request);
        Set<Specialty> specialties = request.getSpecialtyIds().stream()
                .map(id -> specialtyRepository.findById(id)
                        .orElseThrow(() -> new SpecialtyNotFoundException(id)))
                .collect(Collectors.toSet());
        doctor.setSpecialties(specialties);

        if (doctorRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new EmailAlreadyExistsException("Doctor with this email already exists: " + request.getEmail());
        }

        Doctor savedDoctor = doctorRepository.saveAndFlush(doctor);

        DoctorRegistered event = DoctorRegistered.builder()
                .eventId(UUID.randomUUID())
                .doctorId(savedDoctor.getId())
                .firstName(savedDoctor.getFirstName())
                .lastName(savedDoctor.getLastName())
                .registeredAt(savedDoctor.getRegisteredAt())
                .build();
        kafkaTemplate.send("doctor.registered", event);

        return doctorMapper.toDoctorResponse(doctor);
    }

    public DoctorResponse getDoctorById(UUID id) {
        Doctor doctor = doctorRepository.findWithDetailsById(id)
                .orElseThrow(() -> new DoctorNotFoundException(id));
        return doctorMapper.toDoctorResponse(doctor);
    }

    @Transactional(readOnly = true)
    public List<DoctorSummaryResponse> findDoctors(String specialty, Language language) {
        Specification<Doctor> spec = Specification
                .allOf(DoctorSpecifications.hasSpecialty(specialty), DoctorSpecifications.hasLanguage(language));

        return doctorRepository.findAll(spec).stream()
                .map(doctorMapper::toDoctorSummaryResponse)
                .toList();
    }
}
