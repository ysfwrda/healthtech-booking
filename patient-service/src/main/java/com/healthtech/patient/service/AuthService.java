package com.healthtech.patient.service;

import com.healthtech.patient.domain.Patient;
import com.healthtech.patient.dto.AuthResponse;
import com.healthtech.patient.dto.LoginRequest;
import com.healthtech.patient.dto.RegisterRequest;
import com.healthtech.patient.event.PatientRegistered;
import com.healthtech.patient.exception.EmailAlreadyExistsException;
import com.healthtech.patient.exception.InvalidCredentialsException;
import com.healthtech.patient.exception.UsernameAlreadyExistsException;
import com.healthtech.patient.filter.CorrelationIdFilter;
import com.healthtech.patient.mapper.PatientMapper;
import com.healthtech.patient.repository.PatientRepository;
import com.healthtech.patient.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final JwtTokenProvider jwtTokenProvider;
    private final PatientRepository patientRepository;
    private final PatientMapper patientMapper;
    private final PasswordEncoder passwordEncoder;
    private final KafkaTemplate<String, PatientRegistered> kafkaTemplate;

    public AuthResponse register(RegisterRequest registerRequest) {
        Patient patient = patientMapper.toEntity(registerRequest);
        patient.setPasswordHash(passwordEncoder.encode(registerRequest.getPassword()));
        try {
            patientRepository.saveAndFlush(patient);
        } catch (DataIntegrityViolationException ex) {
            if (patientRepository.existsByUsername(patient.getUsername())) {
                throw new UsernameAlreadyExistsException(patient.getUsername());
            }
            if (patientRepository.existsByEmail(patient.getEmail())) {
                throw new EmailAlreadyExistsException(patient.getEmail());
            }
            throw ex;
        }

        String token = jwtTokenProvider.generateToken(patient.getId());
        PatientRegistered event = PatientRegistered.builder()
                .eventId(UUID.randomUUID())
                .patientId(patient.getId())
                .firstName(patient.getFirstName())
                .lastName(patient.getLastName())
                .email(patient.getEmail())
                .registeredAt(patient.getRegisteredAt()).build();
        ProducerRecord<String, PatientRegistered> record =
                new ProducerRecord<>("patient.registered", event);
        record.headers().add(CorrelationIdFilter.CORRELATION_ID_HEADER,
                correlationIdOrGenerate().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record);

        return AuthResponse.builder()
                .username(patient.getUsername())
                .token(token)
                .expiresIn(jwtTokenProvider.getExpirationSeconds())
                .build();
    }

    public AuthResponse login(LoginRequest loginRequest) {
        Patient patient = patientRepository.findByUsername(loginRequest.getUsername())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));
        if (!passwordEncoder.matches(loginRequest.getPassword(), patient.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        String token = jwtTokenProvider.generateToken(patient.getId());
        return AuthResponse.builder()
                .username(patient.getUsername())
                .token(token)
                .expiresIn(jwtTokenProvider.getExpirationSeconds())
                .build();
    }

    // Threads the current request's correlation id onto the outgoing Kafka message so a
    // consumer processing this event can tie its own log lines back to the request that
    // produced it. Falls back to a fresh id outside a request context (e.g. a test),
    // matching CorrelationIdFilter's own fallback for a missing incoming header.
    private String correlationIdOrGenerate() {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return correlationId != null ? correlationId : UUID.randomUUID().toString();
    }
}
