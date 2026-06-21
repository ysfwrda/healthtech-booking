package com.healthtech.appointment.service;

import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.event.AppointmentBooked;
import com.healthtech.appointment.event.AppointmentCancelled;
import com.healthtech.appointment.mapper.AppointmentMapper;
import com.healthtech.appointment.repository.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;
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
    private KafkaTemplate<String, AppointmentBooked> bookedEventKafkaTemplate;

    @Mock
    private KafkaTemplate<String, AppointmentCancelled> cancelledEventKafkaTemplate;

    private AppointmentService appointmentService;

    @BeforeEach
    void setUp() {
        appointmentService = new AppointmentService(
                appointmentRepository,
                appointmentMapper,
                bookedEventKafkaTemplate,
                cancelledEventKafkaTemplate
        );
    }
    @Test
    void bookAppointment_shouldSaveWithPendingStatusAndPublishEvent() {
        // Arrange
        Appointment appointment = Appointment.builder()
                .type(INITIAL_CONSULTATION)
                .build();

        AppointmentRequest request = AppointmentRequest.builder()
                .type(INITIAL_CONSULTATION)
                .build();

        AppointmentResponse response = AppointmentResponse.builder()
                .status(AppointmentStatus.PENDING)
                .build();

        when(appointmentMapper.toEntity(request)).thenReturn(appointment);
        when(appointmentRepository.save(any(Appointment.class))).thenReturn(appointment);
        when(appointmentMapper.toResponse(appointment)).thenReturn(response);

        // Act
        AppointmentResponse result = appointmentService.bookAppointment(request);

        // Assert
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.PENDING);
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
}