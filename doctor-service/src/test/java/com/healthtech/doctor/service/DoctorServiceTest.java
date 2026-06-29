package com.healthtech.doctor.service;

import com.healthtech.doctor.domain.Address;
import com.healthtech.doctor.domain.Doctor;
import com.healthtech.doctor.domain.Language;
import com.healthtech.doctor.domain.Specialty;
import com.healthtech.doctor.dto.AddressDto;
import com.healthtech.doctor.dto.CreateDoctorRequest;
import com.healthtech.doctor.dto.DoctorResponse;
import com.healthtech.doctor.dto.DoctorSummaryResponse;
import com.healthtech.doctor.event.DoctorRegistered;
import com.healthtech.doctor.exception.DoctorNotFoundException;
import com.healthtech.doctor.exception.EmailAlreadyExistsException;
import com.healthtech.doctor.exception.SpecialtyNotFoundException;
import com.healthtech.doctor.mapper.DoctorMapper;
import com.healthtech.doctor.repository.DoctorRepository;
import com.healthtech.doctor.repository.SpecialtyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DoctorServiceTest {

    @Mock private DoctorRepository doctorRepository;
    @Mock private SpecialtyRepository specialtyRepository;
    @Mock private DoctorMapper doctorMapper;
    @Mock private KafkaTemplate<String, DoctorRegistered> kafkaTemplate;

    @InjectMocks
    private DoctorService doctorService;

    private final UUID doctorId = UUID.randomUUID();
    private final UUID specialtyId = UUID.randomUUID();

    private CreateDoctorRequest createRequest;
    private Doctor doctor;
    private DoctorResponse doctorResponse;

    @BeforeEach
    void setUp() {
        createRequest = CreateDoctorRequest.builder()
                .firstName("Anna")
                .lastName("Mueller")
                .email("anna.mueller@example.com")
                .phoneNumber("+49123456789")
                .address(AddressDto.builder()
                        .street("Hauptstrasse")
                        .houseNumber("1")
                        .postalCode("10115")
                        .city("Berlin")
                        .country("DE")
                        .build())
                .specialtyIds(Set.of(specialtyId))
                .build();

        doctor = Doctor.builder()
                .id(doctorId)
                .firstName("Anna")
                .lastName("Mueller")
                .email("anna.mueller@example.com")
                .phoneNumber("+49123456789")
                .address(new Address("Hauptstrasse", "1", "10115", "Berlin", null, "DE"))
                .specialties(Set.of(Specialty.builder().id(specialtyId).name("Cardiology").build()))
                .registeredAt(LocalDateTime.now())
                .build();

        doctorResponse = DoctorResponse.builder()
                .id(doctorId)
                .firstName("Anna")
                .lastName("Mueller")
                .email("anna.mueller@example.com")
                .phoneNumber("+49123456789")
                .registeredAt(doctor.getRegisteredAt())
                .build();
    }

    // -- createDoctor ---------------------------------------------------------

    @Test
    void createDoctor_happyPath_savesAndPublishesEvent() {
        // Arrange
        Specialty specialty = Specialty.builder().id(specialtyId).name("Cardiology").build();
        when(doctorMapper.toEntity(createRequest)).thenReturn(doctor);
        when(specialtyRepository.findById(specialtyId)).thenReturn(Optional.of(specialty));
        when(doctorRepository.saveAndFlush(any(Doctor.class))).thenReturn(doctor);
        when(doctorMapper.toDoctorResponse(doctor)).thenReturn(doctorResponse);

        // Act
        DoctorResponse result = doctorService.createDoctor(createRequest);

        // Assert
        assertThat(result.getId()).isEqualTo(doctorId);
        assertThat(result.getEmail()).isEqualTo("anna.mueller@example.com");
        verify(doctorRepository).saveAndFlush(doctor);
        verify(kafkaTemplate, times(1)).send(eq("doctor.registered"), any(DoctorRegistered.class));
    }

    @Test
    void createDoctor_publishedEvent_containsDoctorIdAndName() {
        // Arrange
        Specialty specialty = Specialty.builder().id(specialtyId).name("Cardiology").build();
        when(doctorMapper.toEntity(createRequest)).thenReturn(doctor);
        when(specialtyRepository.findById(specialtyId)).thenReturn(Optional.of(specialty));
        when(doctorRepository.saveAndFlush(any(Doctor.class))).thenReturn(doctor);
        when(doctorMapper.toDoctorResponse(doctor)).thenReturn(doctorResponse);

        // Act
        doctorService.createDoctor(createRequest);

        // Assert: event payload carries the expected doctor identity fields
        verify(kafkaTemplate).send(eq("doctor.registered"), argThat(event ->
                event.getDoctorId().equals(doctorId)
                && "Anna".equals(event.getFirstName())
                && "Mueller".equals(event.getLastName())
                && event.getEventId() != null
        ));
    }

    @Test
    void createDoctor_specialtyNotFound_throws() {
        when(doctorMapper.toEntity(createRequest)).thenReturn(doctor);
        when(specialtyRepository.findById(specialtyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> doctorService.createDoctor(createRequest))
                .isInstanceOf(SpecialtyNotFoundException.class);

        verify(doctorRepository, never()).saveAndFlush(any());
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    @Test
    void createDoctor_emailAlreadyExists_throwsEmailAlreadyExistsException() {
        // Arrange
        Specialty specialty = Specialty.builder().id(specialtyId).name("Cardiology").build();
        when(doctorMapper.toEntity(createRequest)).thenReturn(doctor);
        when(specialtyRepository.findById(specialtyId)).thenReturn(Optional.of(specialty));
        when(doctorRepository.findByEmail(createRequest.getEmail())).thenReturn(Optional.of(doctor));

        // Act & Assert
        assertThatThrownBy(() -> doctorService.createDoctor(createRequest))
                .isInstanceOf(EmailAlreadyExistsException.class)
                .hasMessageContaining("anna.mueller@example.com");
        verify(doctorRepository, never()).saveAndFlush(any());
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    // -- findDoctors ----------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void findDoctors_withSpecialtyAndLanguage_returnsMappedSummaries() {
        // Arrange
        DoctorSummaryResponse summary = DoctorSummaryResponse.builder()
                .id(doctorId).firstName("Anna").lastName("Mueller").build();
        when(doctorRepository.findAll(any(Specification.class))).thenReturn(List.of(doctor));
        when(doctorMapper.toDoctorSummaryResponse(doctor)).thenReturn(summary);

        // Act
        List<DoctorSummaryResponse> results = doctorService.findDoctors("Cardiology", Language.GERMAN);

        // Assert
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getId()).isEqualTo(doctorId);
        verify(doctorRepository).findAll(any(Specification.class));
        verify(doctorMapper).toDoctorSummaryResponse(doctor);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findDoctors_noMatchingDoctors_returnsEmptyList() {
        // Arrange
        when(doctorRepository.findAll(any(Specification.class))).thenReturn(List.of());

        // Act
        List<DoctorSummaryResponse> results = doctorService.findDoctors(null, null);

        // Assert
        assertThat(results).isEmpty();
        verify(doctorMapper, never()).toDoctorSummaryResponse(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findDoctors_nullFilters_delegatesToRepositoryWithoutError() {
        // Arrange: null specialty and null language produce a pass-through spec
        when(doctorRepository.findAll(any(Specification.class))).thenReturn(List.of(doctor));
        DoctorSummaryResponse summary = DoctorSummaryResponse.builder().id(doctorId).build();
        when(doctorMapper.toDoctorSummaryResponse(doctor)).thenReturn(summary);

        // Act
        List<DoctorSummaryResponse> results = doctorService.findDoctors(null, null);

        // Assert
        assertThat(results).hasSize(1);
    }

    // -- getDoctorById --------------------------------------------------------

    @Test
    void getDoctorById_doctorExists_returnsDoctorResponse() {
        // Arrange
        when(doctorRepository.findWithDetailsById(doctorId)).thenReturn(Optional.of(doctor));
        when(doctorMapper.toDoctorResponse(doctor)).thenReturn(doctorResponse);

        // Act
        DoctorResponse result = doctorService.getDoctorById(doctorId);

        // Assert
        assertThat(result.getId()).isEqualTo(doctorId);
        assertThat(result.getFirstName()).isEqualTo("Anna");
        verify(doctorRepository).findWithDetailsById(doctorId);
        verify(doctorMapper).toDoctorResponse(doctor);
    }

    @Test
    void getDoctorById_doctorNotFound_throwsDoctorNotFoundException() {
        // Arrange
        UUID unknownId = UUID.randomUUID();
        when(doctorRepository.findWithDetailsById(unknownId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> doctorService.getDoctorById(unknownId))
                .isInstanceOf(DoctorNotFoundException.class)
                .hasMessageContaining(unknownId.toString());
    }
}
