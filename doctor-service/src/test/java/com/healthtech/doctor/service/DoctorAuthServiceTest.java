package com.healthtech.doctor.service;

import com.healthtech.doctor.domain.Address;
import com.healthtech.doctor.domain.Doctor;
import com.healthtech.doctor.domain.Language;
import com.healthtech.doctor.domain.OpeningHours;
import com.healthtech.doctor.domain.Specialty;
import com.healthtech.doctor.dto.AddressDto;
import com.healthtech.doctor.dto.DoctorAuthResponse;
import com.healthtech.doctor.dto.DoctorLoginRequest;
import com.healthtech.doctor.dto.DoctorRegistrationRequest;
import com.healthtech.doctor.dto.OpeningHoursDto;
import com.healthtech.doctor.event.DoctorRegistered;
import com.healthtech.doctor.exception.EmailAlreadyExistsException;
import com.healthtech.doctor.exception.InvalidCredentialsException;
import com.healthtech.doctor.exception.SpecialtyNotFoundException;
import com.healthtech.doctor.mapper.DoctorMapper;
import com.healthtech.doctor.repository.DoctorRepository;
import com.healthtech.doctor.repository.SpecialtyRepository;
import com.healthtech.doctor.security.DoctorTokenProvider;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DoctorAuthServiceTest {

    @Mock private DoctorRepository doctorRepository;
    @Mock private SpecialtyRepository specialtyRepository;
    @Mock private DoctorMapper doctorMapper;
    @Mock private DoctorTokenProvider doctorTokenProvider;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private org.springframework.kafka.core.KafkaTemplate<String, DoctorRegistered> kafkaTemplate;

    @InjectMocks
    private DoctorAuthService doctorAuthService;

    private final UUID doctorId = UUID.randomUUID();
    private final UUID specialtyId = UUID.randomUUID();

    private DoctorRegistrationRequest registrationRequest;
    private Doctor doctor;

    @BeforeEach
    void setUp() {
        registrationRequest = DoctorRegistrationRequest.builder()
                .firstName("Anna")
                .lastName("Mueller")
                .email("anna.mueller@example.com")
                .password("secret123")
                .phoneNumber("+49123456789")
                .address(AddressDto.builder()
                        .street("Hauptstrasse")
                        .houseNumber("1")
                        .postalCode("10115")
                        .city("Berlin")
                        .country("DE")
                        .build())
                .specialtyIds(Set.of(specialtyId))
                .openingHours(Set.of(OpeningHoursDto.builder()
                        .dayOfWeek(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build()))
                .languages(Set.of(Language.GERMAN))
                .build();

        doctor = Doctor.builder()
                .id(doctorId)
                .firstName("Anna")
                .lastName("Mueller")
                .email("anna.mueller@example.com")
                .passwordHash("$2a$raw")
                .phoneNumber("+49123456789")
                .address(new Address("Hauptstrasse", "1", "10115", "Berlin", null, "DE"))
                .openingHours(Set.of(OpeningHours.builder()
                        .dayOfWeek(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build()))
                .registeredAt(LocalDateTime.now())
                .build();
    }

    // -- register ---------------------------------------------------------------

    @Test
    void register_happyPath_savesHashesPublishesAndReturnsToken() {
        // Arrange
        Specialty specialty = Specialty.builder().id(specialtyId).name("Cardiology").build();
        when(doctorMapper.toEntity(registrationRequest)).thenReturn(doctor);
        when(passwordEncoder.encode("secret123")).thenReturn("$2a$hashed");
        when(specialtyRepository.findById(specialtyId)).thenReturn(Optional.of(specialty));
        when(doctorRepository.saveAndFlush(doctor)).thenReturn(doctor);
        when(doctorTokenProvider.generateToken(doctorId)).thenReturn("jwt-token");
        when(doctorTokenProvider.getExpirationSeconds()).thenReturn(3600L);

        // Act
        DoctorAuthResponse response = doctorAuthService.register(registrationRequest);

        // Assert
        assertThat(response.getId()).isEqualTo(doctorId);
        assertThat(response.getEmail()).isEqualTo("anna.mueller@example.com");
        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getExpiresIn()).isEqualTo(3600L);
        verify(doctorRepository).saveAndFlush(doctor);
        ArgumentMatcher<ProducerRecord<String, DoctorRegistered>> matchesRegisteredRecord = record ->
                record.topic().equals("doctor.registered") && record.value() instanceof DoctorRegistered;
        verify(kafkaTemplate, times(1)).send(argThat(matchesRegisteredRecord));
    }

    @Test
    void register_passwordIsStoredAsHash_notPlaintext() {
        // Arrange
        Specialty specialty = Specialty.builder().id(specialtyId).name("Cardiology").build();
        when(doctorMapper.toEntity(registrationRequest)).thenReturn(doctor);
        when(passwordEncoder.encode("secret123")).thenReturn("$2a$hashed");
        when(specialtyRepository.findById(specialtyId)).thenReturn(Optional.of(specialty));
        when(doctorRepository.saveAndFlush(any(Doctor.class))).thenReturn(doctor);
        when(doctorTokenProvider.generateToken(any())).thenReturn("jwt-token");
        when(doctorTokenProvider.getExpirationSeconds()).thenReturn(3600L);

        // Act
        doctorAuthService.register(registrationRequest);

        // Assert
        verify(doctorRepository).saveAndFlush(argThat(d -> "$2a$hashed".equals(d.getPasswordHash())));
    }

    @Test
    void register_specialtyNotFound_throwsAndNeverSaves() {
        // Arrange
        when(doctorMapper.toEntity(registrationRequest)).thenReturn(doctor);
        when(passwordEncoder.encode("secret123")).thenReturn("$2a$hashed");
        when(specialtyRepository.findById(specialtyId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> doctorAuthService.register(registrationRequest))
                .isInstanceOf(SpecialtyNotFoundException.class);

        verify(doctorRepository, never()).saveAndFlush(any());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    void register_emailAlreadyExists_throwsEmailAlreadyExistsException() {
        // Arrange
        Specialty specialty = Specialty.builder().id(specialtyId).name("Cardiology").build();
        when(doctorMapper.toEntity(registrationRequest)).thenReturn(doctor);
        when(passwordEncoder.encode("secret123")).thenReturn("$2a$hashed");
        when(specialtyRepository.findById(specialtyId)).thenReturn(Optional.of(specialty));
        when(doctorRepository.saveAndFlush(any(Doctor.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        // Act & Assert
        assertThatThrownBy(() -> doctorAuthService.register(registrationRequest))
                .isInstanceOf(EmailAlreadyExistsException.class)
                .hasMessageContaining("anna.mueller@example.com");

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(doctorTokenProvider, never()).generateToken(any());
    }

    // -- login --------------------------------------------------------------------

    @Test
    void login_validCredentials_returnsAuthResponse() {
        // Arrange
        DoctorLoginRequest loginRequest = new DoctorLoginRequest("anna.mueller@example.com", "secret123");
        when(doctorRepository.findByEmail("anna.mueller@example.com")).thenReturn(Optional.of(doctor));
        when(passwordEncoder.matches("secret123", "$2a$raw")).thenReturn(true);
        when(doctorTokenProvider.generateToken(doctorId)).thenReturn("jwt-token");
        when(doctorTokenProvider.getExpirationSeconds()).thenReturn(3600L);

        // Act
        DoctorAuthResponse response = doctorAuthService.login(loginRequest);

        // Assert
        assertThat(response.getId()).isEqualTo(doctorId);
        assertThat(response.getEmail()).isEqualTo("anna.mueller@example.com");
        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getExpiresIn()).isEqualTo(3600L);
    }

    @Test
    void login_unknownEmail_throwsInvalidCredentialsException() {
        // Arrange
        DoctorLoginRequest loginRequest = new DoctorLoginRequest("ghost@example.com", "secret123");
        when(doctorRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> doctorAuthService.login(loginRequest))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentialsException() {
        // Arrange
        DoctorLoginRequest loginRequest = new DoctorLoginRequest("anna.mueller@example.com", "wrongpass");
        when(doctorRepository.findByEmail("anna.mueller@example.com")).thenReturn(Optional.of(doctor));
        when(passwordEncoder.matches("wrongpass", "$2a$raw")).thenReturn(false);

        // Act & Assert
        assertThatThrownBy(() -> doctorAuthService.login(loginRequest))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(doctorTokenProvider, never()).generateToken(any());
    }
}
