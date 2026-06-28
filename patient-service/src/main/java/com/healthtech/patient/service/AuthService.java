package com.healthtech.patient.service;

import com.healthtech.patient.domain.Patient;
import com.healthtech.patient.dto.AuthResponse;
import com.healthtech.patient.dto.LoginRequest;
import com.healthtech.patient.dto.RegisterRequest;
import com.healthtech.patient.event.PatientRegistered;
import com.healthtech.patient.exception.InvalidCredentialsException;
import com.healthtech.patient.exception.UsernameAlreadyExistsException;
import com.healthtech.patient.mapper.PatientMapper;
import com.healthtech.patient.repository.PatientRepository;
import com.healthtech.patient.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
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
       if(patientRepository.findByUsername(registerRequest.getUsername()).isPresent()){
           throw new UsernameAlreadyExistsException("Username already exists");
       }
        Patient patient = patientMapper.toEntity(registerRequest);
        patient.setPasswordHash(passwordEncoder.encode(registerRequest.getPassword()));
        patientRepository.save(patient);
        String token = jwtTokenProvider.generateToken(patient.getId());
        PatientRegistered event = PatientRegistered.builder()
                .eventId(UUID.randomUUID())
                .patientId(patient.getId())
                .firstName(patient.getFirstName())
                .lastName(patient.getLastName())
                .email(patient.getEmail())
                .registeredAt(patient.getRegisteredAt()).build();
        kafkaTemplate.send("patient.registered", event);

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
}
