package com.healthtech.appointment.service;

import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.event.AppointmentBooked;
import com.healthtech.appointment.event.AppointmentCancelled;
import com.healthtech.appointment.mapper.AppointmentMapper;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.readmodel.ValidPatient;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
import com.healthtech.appointment.repository.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.healthtech.appointment.domain.AppointmentType.INITIAL_CONSULTATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private AppointmentMapper appointmentMapper;

    @Mock
    private ValidPatientRepository validPatientRepository;

    @Mock
    private ValidDoctorRepository validDoctorRepository;

    @Mock
    private KafkaTemplate<String, AppointmentBooked> bookedEventKafkaTemplate;

    @Mock
    private KafkaTemplate<String, AppointmentCancelled> cancelledEventKafkaTemplate;

    private AppointmentService appointmentService;

    @BeforeEach
    void setUp() {
        appointmentService = new AppointmentService(
                appointmentRepository,
                appointmentMapper,
                validPatientRepository,
                validDoctorRepository,
                bookedEventKafkaTemplate,
                cancelledEventKafkaTemplate
        );
    }

    private void stubValidReadModel(UUID patientId, UUID doctorId, LocalDateTime dateTime) {
        when(validPatientRepository.findById(patientId)).thenReturn(Optional.of(
                ValidPatient.builder().patientId(patientId).firstName("Jane").lastName("Doe").email("jane@example.com").build()));
        when(validDoctorRepository.findById(doctorId)).thenReturn(Optional.of(
                ValidDoctor.builder()
                        .doctorId(doctorId)
                        .firstName("John")
                        .lastName("Smith")
                        .openingHours(Set.of(OpeningHours.builder()
                                .dayOfWeek(dateTime.getDayOfWeek())
                                .startTime(LocalTime.of(8, 0))
                                .endTime(LocalTime.of(18, 0))
                                .build()))
                        .build()));
    }

    @Test
    void bookAppointment_shouldSaveWithConfirmedStatusAndPublishEvent() {
        // Arrange
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        LocalDateTime dateTime = LocalDateTime.of(2026, 8, 10, 10, 0);
        Appointment appointment = Appointment.builder()
                .patientId(patientId)
                .doctorId(doctorId)
                .dateTime(dateTime)
                .type(INITIAL_CONSULTATION)
                .build();

        AppointmentRequest request = AppointmentRequest.builder()
                .patientId(patientId)
                .doctorId(doctorId)
                .dateTime(dateTime)
                .type(INITIAL_CONSULTATION)
                .build();

        AppointmentResponse response = AppointmentResponse.builder()
                .status(AppointmentStatus.CONFIRMED)
                .build();

        stubValidReadModel(patientId, doctorId, dateTime);
        when(appointmentMapper.toEntity(request)).thenReturn(appointment);
        when(appointmentRepository.save(any(Appointment.class))).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(response);

        // Act
        AppointmentResponse result = appointmentService.bookAppointment(request);

        // Assert
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        verify(appointmentRepository, times(1)).save(appointment);
        verify(bookedEventKafkaTemplate, times(1)).send(eq("appointment.booked"), any(AppointmentBooked.class));    }

    @Test
    void cancelAppointment_shouldUpdateStatusToCancelledAndPublishEvent() {
        // Arrange
        Appointment appointment = Appointment.builder()
                .type(INITIAL_CONSULTATION)
                .id(UUID.randomUUID())
                .build();

        AppointmentResponse response = AppointmentResponse.builder()
                .status(AppointmentStatus.CANCELLED)
                .build();

        when(appointmentRepository.findById(appointment.getId())).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(appointment)).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(response);

        // Act
        AppointmentResponse result = appointmentService.cancelAppointment(appointment.getId());

        // Assert
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        verify(appointmentRepository, times(1)).save(appointment);
        verify(cancelledEventKafkaTemplate, times(1)).send(eq("appointment.cancelled"), any(AppointmentCancelled.class));
    }

    @Test
    void cancelAppointment_appointmentNotFound_shouldThrowRuntimeExceptionWithMessage() {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.empty());

        // Act and Assert
        assertThatThrownBy(() -> appointmentService.cancelAppointment(appointmentId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Appointment not found: " + appointmentId);

        verify(appointmentRepository, never()).save(any());
        verify(cancelledEventKafkaTemplate, never()).send(any(), any());
    }

    @Test
    void bookAppointment_shouldPublishEventWithCorrectAppointmentFields() {
        // Arrange
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        LocalDateTime dateTime = LocalDateTime.of(2026, 8, 10, 10, 0);
        Appointment appointment = Appointment.builder()
                .id(UUID.randomUUID())
                .patientId(patientId)
                .doctorId(doctorId)
                .dateTime(dateTime)
                .duration(30)
                .type(INITIAL_CONSULTATION)
                .createdAt(LocalDateTime.now())
                .build();

        AppointmentRequest request = AppointmentRequest.builder()
                .patientId(patientId)
                .doctorId(doctorId)
                .dateTime(dateTime)
                .type(INITIAL_CONSULTATION)
                .build();

        stubValidReadModel(patientId, doctorId, dateTime);
        when(appointmentMapper.toEntity(request)).thenReturn(appointment);
        when(appointmentRepository.save(any(Appointment.class))).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(AppointmentResponse.builder().build());

        // Act
        appointmentService.bookAppointment(request);

        // Assert: event carries the saved appointment's IDs
        ArgumentCaptor<AppointmentBooked> eventCaptor = ArgumentCaptor.forClass(AppointmentBooked.class);
        verify(bookedEventKafkaTemplate).send(eq("appointment.booked"), eventCaptor.capture());
        AppointmentBooked event = eventCaptor.getValue();
        assertThat(event.getAppointmentId()).isEqualTo(appointment.getId());
        assertThat(event.getPatientId()).isEqualTo(patientId);
        assertThat(event.getDoctorId()).isEqualTo(doctorId);
        assertThat(event.getDuration()).isEqualTo(30);
        assertThat(event.getPatientName()).isEqualTo("Jane Doe");
        assertThat(event.getPatientEmail()).isEqualTo("jane@example.com");
        assertThat(event.getDoctorName()).isEqualTo("John Smith");
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getBookedAt()).isNotNull();
    }

    @Test
    void bookAppointment_shouldSetDurationToThirtyServerSide() {
        // Arrange
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        LocalDateTime dateTime = LocalDateTime.of(2026, 8, 10, 10, 0);
        Appointment appointment = Appointment.builder()
                .patientId(patientId)
                .doctorId(doctorId)
                .dateTime(dateTime)
                .type(INITIAL_CONSULTATION)
                .build();

        AppointmentRequest request = AppointmentRequest.builder()
                .patientId(patientId)
                .doctorId(doctorId)
                .dateTime(dateTime)
                .type(INITIAL_CONSULTATION)
                .build();

        stubValidReadModel(patientId, doctorId, dateTime);
        when(appointmentMapper.toEntity(request)).thenReturn(appointment);
        when(appointmentRepository.save(any(Appointment.class))).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(AppointmentResponse.builder().build());

        // Act
        appointmentService.bookAppointment(request);

        // Assert: duration is server-set regardless of request content
        assertThat(appointment.getDuration()).isEqualTo(30);
    }

    @Test
    void cancelAppointment_alreadyCancelledAppointment_shouldOverwriteStatusAndPublishEvent() {
        // Documents current behavior: no guard against double-cancellation.
        // Arrange
        Appointment appointment = Appointment.builder()
                .id(UUID.randomUUID())
                .type(INITIAL_CONSULTATION)
                .status(AppointmentStatus.CANCELLED)
                .build();

        when(appointmentRepository.findById(appointment.getId())).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(appointment)).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(
                AppointmentResponse.builder().status(AppointmentStatus.CANCELLED).build());

        // Act
        AppointmentResponse result = appointmentService.cancelAppointment(appointment.getId());

        // Assert
        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        verify(cancelledEventKafkaTemplate, times(1)).send(eq("appointment.cancelled"), any(AppointmentCancelled.class));
    }
}
