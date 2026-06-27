package com.healthtech.patient.service;

import com.healthtech.patient.domain.InsuranceType;
import com.healthtech.patient.domain.Patient;
import com.healthtech.patient.dto.PatientResponse;
import com.healthtech.patient.exception.PatientNotFoundException;
import com.healthtech.patient.mapper.PatientMapper;
import com.healthtech.patient.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientServiceTest {

    @Mock private PatientRepository patientRepository;
    @Mock private PatientMapper patientMapper;

    @InjectMocks
    private PatientService patientService;

    private UUID patientId;
    private Patient patient;
    private PatientResponse patientResponse;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();

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

        patientResponse = new PatientResponse(
                patientId,
                "John",
                "Doe",
                InsuranceType.STATUTORY,
                LocalDate.of(1990, 1, 15),
                "john@example.com",
                LocalDateTime.now()
        );
    }

    @Test
    void getPatientProfileById_patientExists_returnsPatientResponse() {
        // Arrange
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(patientMapper.toPatientResponse(patient)).thenReturn(patientResponse);

        // Act
        PatientResponse result = patientService.getPatientProfileById(patientId);

        // Assert
        assertThat(result.getId()).isEqualTo(patientId);
        assertThat(result.getFirstName()).isEqualTo("John");
        assertThat(result.getLastName()).isEqualTo("Doe");
        assertThat(result.getEmail()).isEqualTo("john@example.com");
        assertThat(result.getInsuranceType()).isEqualTo(InsuranceType.STATUTORY);
        verify(patientRepository).findById(patientId);
        verify(patientMapper).toPatientResponse(patient);
    }

    @Test
    void getPatientProfileById_patientNotFound_throwsPatientNotFoundException() {
        // Arrange
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> patientService.getPatientProfileById(patientId))
                .isInstanceOf(PatientNotFoundException.class)
                .hasMessageContaining(patientId.toString());
    }
}
