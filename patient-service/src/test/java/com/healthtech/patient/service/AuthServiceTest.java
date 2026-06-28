package com.healthtech.patient.service;

import com.healthtech.patient.domain.InsuranceType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private PatientRepository patientRepository;
    @Mock private PatientMapper patientMapper;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private KafkaTemplate<String, PatientRegistered> patientRegisteredKafkaTemplate;

    @InjectMocks
    private AuthService authService;

    private RegisterRequest registerRequest;
    private Patient patient;
    private final UUID patientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        registerRequest = RegisterRequest.builder()
                .firstName("John")
                .lastName("Doe")
                .username("johndoe")
                .password("secret123")
                .email("john@example.com")
                .dateOfBirth(LocalDate.of(1990, 1, 15))
                .insuranceType(InsuranceType.STATUTORY)
                .build();

        patient = Patient.builder()
                .id(patientId)
                .firstName("John")
                .lastName("Doe")
                .username("johndoe")
                .email("john@example.com")
                .passwordHash("$2a$hashed")
                .dateOfBirth(LocalDate.of(1990, 1, 15))
                .insuranceType(InsuranceType.STATUTORY)
                .build();
    }

    // ── register ─────────────────────────────────────────────────────────────

    @Test
    void register_happyPath_createsPatientAndHashesPassword() {
        // Arrange
        when(patientRepository.findByUsername("johndoe")).thenReturn(Optional.empty());
        when(patientMapper.toEntity(registerRequest)).thenReturn(patient);
        when(passwordEncoder.encode("secret123")).thenReturn("$2a$hashed");
        when(patientRepository.save(any(Patient.class))).thenReturn(patient);
        when(jwtTokenProvider.generateToken(patientId)).thenReturn("jwt-token");
        when(jwtTokenProvider.getExpirationSeconds()).thenReturn(3600L);

        // Act
        AuthResponse response = authService.register(registerRequest);

        // Assert
        assertThat(response.getUsername()).isEqualTo("johndoe");
        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getExpiresIn()).isEqualTo(3600L);
        verify(passwordEncoder).encode("secret123");
        verify(patientRepository).save(patient);
        verify(jwtTokenProvider).generateToken(patientId);
        verify(patientRegisteredKafkaTemplate, times(1)).send(eq("patient.registered"),any(PatientRegistered.class));
    }

    @Test
    void register_userExists_throwsUsernameAlreadyExistsException() {
        // Arrange
        when(patientRepository.findByUsername("johndoe")).thenReturn(Optional.of(patient));

        // Act & Assert
        assertThatThrownBy(() -> authService.register(registerRequest))
                .isInstanceOf(UsernameAlreadyExistsException.class)
                .hasMessageContaining("Username already exists");
        verify(patientRepository, never()).save(any());
        verify(jwtTokenProvider, never()).generateToken(any());
    }

    @Test
    void register_passwordIsStoredAsHash_notPlaintext() {
        // Arrange
        when(patientRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(patientMapper.toEntity(registerRequest)).thenReturn(patient);
        when(passwordEncoder.encode("secret123")).thenReturn("$2a$hashed");
        when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtTokenProvider.generateToken(any())).thenReturn("jwt-token");
        when(jwtTokenProvider.getExpirationSeconds()).thenReturn(3600L);

        // Act
        authService.register(registerRequest);

        // Assert — the entity saved must carry the hashed value, never the raw password
        verify(patientRepository).save(argThat(p -> "$2a$hashed".equals(p.getPasswordHash())));
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returnsAuthResponse() {
        // Arrange
        LoginRequest loginRequest = new LoginRequest("johndoe", "secret123");
        when(patientRepository.findByUsername("johndoe")).thenReturn(Optional.of(patient));
        when(passwordEncoder.matches("secret123", "$2a$hashed")).thenReturn(true);
        when(jwtTokenProvider.generateToken(patientId)).thenReturn("jwt-token");
        when(jwtTokenProvider.getExpirationSeconds()).thenReturn(3600L);

        // Act
        AuthResponse response = authService.login(loginRequest);

        // Assert
        assertThat(response.getUsername()).isEqualTo("johndoe");
        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getExpiresIn()).isEqualTo(3600L);
    }

    @Test
    void login_unknownUsername_throwsInvalidCredentialsException() {
        // Arrange
        LoginRequest loginRequest = new LoginRequest("ghost", "secret123");
        when(patientRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Invalid username or password");
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentialsException() {
        // Arrange
        LoginRequest loginRequest = new LoginRequest("johndoe", "wrongpass");
        when(patientRepository.findByUsername("johndoe")).thenReturn(Optional.of(patient));
        when(passwordEncoder.matches("wrongpass", "$2a$hashed")).thenReturn(false);

        // Act & Assert
        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Invalid username or password");
        verify(jwtTokenProvider, never()).generateToken(any());
    }
}
