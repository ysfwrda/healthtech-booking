package com.healthtech.doctor.service;

import com.healthtech.doctor.domain.Doctor;
import com.healthtech.doctor.domain.Specialty;
import com.healthtech.doctor.dto.DoctorAuthResponse;
import com.healthtech.doctor.dto.DoctorLoginRequest;
import com.healthtech.doctor.dto.DoctorRegistrationRequest;
import com.healthtech.doctor.event.DoctorRegistered;
import com.healthtech.doctor.event.OpeningHoursData;
import com.healthtech.doctor.exception.EmailAlreadyExistsException;
import com.healthtech.doctor.exception.InvalidCredentialsException;
import com.healthtech.doctor.exception.SpecialtyNotFoundException;
import com.healthtech.doctor.mapper.DoctorMapper;
import com.healthtech.doctor.repository.DoctorRepository;
import com.healthtech.doctor.repository.SpecialtyRepository;
import com.healthtech.doctor.security.DoctorTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DoctorAuthService {

    private final DoctorRepository doctorRepository;
    private final SpecialtyRepository specialtyRepository;
    private final DoctorMapper doctorMapper;
    private final DoctorTokenProvider doctorTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final KafkaTemplate<String, DoctorRegistered> kafkaTemplate;

    @Transactional
    public DoctorAuthResponse register(DoctorRegistrationRequest request) {
        Doctor doctor = doctorMapper.toEntity(request);
        doctor.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        Set<Specialty> specialties = request.getSpecialtyIds().stream()
                .map(id -> specialtyRepository.findById(id)
                        .orElseThrow(() -> new SpecialtyNotFoundException(id)))
                .collect(Collectors.toSet());
        doctor.setSpecialties(specialties);

        Doctor savedDoctor;
        try {
            savedDoctor = doctorRepository.saveAndFlush(doctor);
        } catch (DataIntegrityViolationException ex) {
            throw new EmailAlreadyExistsException(
                    "Doctor with this email already exists: " + request.getEmail());
        }

        Set<OpeningHoursData> openingHours = savedDoctor.getOpeningHours() == null ? Set.of() :
                savedDoctor.getOpeningHours().stream()
                        .map(oh -> OpeningHoursData.builder()
                                .dayOfWeek(oh.getDayOfWeek())
                                .startTime(oh.getStartTime())
                                .endTime(oh.getEndTime())
                                .build())
                        .collect(Collectors.toSet());

        DoctorRegistered event = DoctorRegistered.builder()
                .eventId(UUID.randomUUID())
                .doctorId(savedDoctor.getId())
                .firstName(savedDoctor.getFirstName())
                .lastName(savedDoctor.getLastName())
                .openingHours(openingHours)
                .registeredAt(savedDoctor.getRegisteredAt())
                .build();
        kafkaTemplate.send("doctor.registered", event);

        String token = doctorTokenProvider.generateToken(savedDoctor.getId());
        return DoctorAuthResponse.builder()
                .id(savedDoctor.getId())
                .email(savedDoctor.getEmail())
                .token(token)
                .expiresIn(doctorTokenProvider.getExpirationSeconds())
                .build();
    }

    public DoctorAuthResponse login(DoctorLoginRequest request) {
        Doctor doctor = doctorRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));
        if (!passwordEncoder.matches(request.getPassword(), doctor.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        String token = doctorTokenProvider.generateToken(doctor.getId());
        return DoctorAuthResponse.builder()
                .id(doctor.getId())
                .email(doctor.getEmail())
                .token(token)
                .expiresIn(doctorTokenProvider.getExpirationSeconds())
                .build();
    }
}
