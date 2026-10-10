package com.healthtech.notification.service;

import com.healthtech.notification.domain.Notification;
import com.healthtech.notification.domain.NotificationType;
import com.healthtech.notification.event.AppointmentBooked;
import com.healthtech.notification.event.AppointmentCancelled;
import com.healthtech.notification.event.AppointmentChanged;
import com.healthtech.notification.repository.NotificationRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationRepository);
    }

    @Test
    void createForBookedAppointment_shouldSaveNotificationWithBookedType() {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        LocalDateTime dateTime = LocalDateTime.of(2026, 8, 15, 10, 30);

        AppointmentBooked event = AppointmentBooked.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(appointmentId)
                .patientId(patientId)
                .doctorId(doctorId)
                .dateTime(dateTime)
                .bookedAt(LocalDateTime.now())
                .build();

        // Act
        notificationService.record(event);

        // Assert
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());

        Notification saved = captor.getValue();
        assertThat(saved.getAppointmentId()).isEqualTo(appointmentId);
        assertThat(saved.getPatientId()).isEqualTo(patientId);
        assertThat(saved.getDoctorId()).isEqualTo(doctorId);
        assertThat(saved.getType()).isEqualTo(NotificationType.APPOINTMENT_BOOKED);
        assertThat(saved.getMessage()).contains(dateTime.toString());
    }

    @Test
    void createForCancelledAppointment_shouldSaveNotificationWithCancelledType() {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        LocalDateTime dateTime = LocalDateTime.of(2026, 8, 15, 10, 30);

        AppointmentCancelled event = AppointmentCancelled.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(appointmentId)
                .patientId(patientId)
                .doctorId(doctorId)
                .dateTime(dateTime)
                .cancelledAt(LocalDateTime.now())
                .build();

        // Act
        notificationService.record(event);

        // Assert
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());

        Notification saved = captor.getValue();
        assertThat(saved.getAppointmentId()).isEqualTo(appointmentId);
        assertThat(saved.getPatientId()).isEqualTo(patientId);
        assertThat(saved.getDoctorId()).isEqualTo(doctorId);
        assertThat(saved.getType()).isEqualTo(NotificationType.APPOINTMENT_CANCELLED);
        assertThat(saved.getMessage()).contains(dateTime.toString());
    }

    @Test
    void createForBookedAppointment_shouldNotSetCancelledType() {
        // Arrange
        AppointmentBooked event = AppointmentBooked.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(UUID.randomUUID())
                .patientId(UUID.randomUUID())
                .doctorId(UUID.randomUUID())
                .dateTime(LocalDateTime.now().plusDays(3))
                .build();

        // Act
        notificationService.record(event);

        // Assert
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getType()).isNotEqualTo(NotificationType.APPOINTMENT_CANCELLED);
    }

    @Test
    void createForCancelledAppointment_shouldNotSetBookedType() {
        // Arrange
        AppointmentCancelled event = cancelledEvent();

        // Act
        notificationService.record(event);

        // Assert
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getType()).isNotEqualTo(NotificationType.APPOINTMENT_BOOKED);
    }

    @Test
    void record_changedEvent_savesNotificationWithChangedTypeAndNewTime() {
        // Arrange
        LocalDateTime newTime = LocalDateTime.of(2026, 8, 16, 11, 0);
        AppointmentChanged event = AppointmentChanged.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(UUID.randomUUID())
                .patientId(UUID.randomUUID())
                .doctorId(UUID.randomUUID())
                .type("FOLLOW_UP")
                .dateTime(newTime)
                .previousType("INITIAL_CONSULTATION")
                .previousDateTime(LocalDateTime.of(2026, 8, 15, 10, 30))
                .changedAt(LocalDateTime.now())
                .build();

        // Act
        notificationService.record(event);

        // Assert
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.APPOINTMENT_CHANGED);
        assertThat(captor.getValue().getMessage()).isEqualTo("Appointment changed to " + newTime);
    }

    @Test
    void record_bookedEvent_storesTheEventIdAsDeduplicationKey() {
        // Arrange
        UUID eventId = UUID.randomUUID();
        AppointmentBooked event = AppointmentBooked.builder()
                .eventId(eventId)
                .appointmentId(UUID.randomUUID())
                .patientId(UUID.randomUUID())
                .doctorId(UUID.randomUUID())
                .dateTime(LocalDateTime.now().plusDays(3))
                .build();

        // Act
        notificationService.record(event);

        // Assert
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
    }

    @Test
    void record_eventIdAlreadyStored_ignoresTheDuplicateWithoutThrowing() {
        // Arrange
        AppointmentCancelled event = cancelledEvent();
        when(notificationRepository.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate",
                        new ConstraintViolationException("duplicate key", new SQLException("duplicate", "23505"),
                                NotificationService.EVENT_ID_INDEX)));

        // Act & Assert
        assertThatCode(() -> notificationService.record(event)).doesNotThrowAnyException();
    }

    @Test
    void record_otherConstraintViolation_isRethrown() {
        // Arrange
        AppointmentCancelled event = cancelledEvent();
        DataIntegrityViolationException other = new DataIntegrityViolationException("other",
                new ConstraintViolationException("violates", new SQLException("other", "23502"), "some_other_constraint"));
        when(notificationRepository.saveAndFlush(any(Notification.class))).thenThrow(other);

        // Act & Assert
        assertThatThrownBy(() -> notificationService.record(event)).isSameAs(other);
    }

    @Test
    void record_integrityViolationWithoutConstraintName_isRethrown() {
        // Arrange
        AppointmentCancelled event = cancelledEvent();
        DataIntegrityViolationException unnamed = new DataIntegrityViolationException("no cause");
        when(notificationRepository.saveAndFlush(any(Notification.class))).thenThrow(unnamed);

        // Act & Assert
        assertThatThrownBy(() -> notificationService.record(event)).isSameAs(unnamed);
    }

    @Test
    void record_databaseUnavailable_isRethrown() {
        // Arrange
        AppointmentCancelled event = cancelledEvent();
        when(notificationRepository.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        // Act & Assert
        assertThatThrownBy(() -> notificationService.record(event)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    private static AppointmentCancelled cancelledEvent() {
        return AppointmentCancelled.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(UUID.randomUUID())
                .patientId(UUID.randomUUID())
                .doctorId(UUID.randomUUID())
                .dateTime(LocalDateTime.now().plusDays(3))
                .build();
    }
}
