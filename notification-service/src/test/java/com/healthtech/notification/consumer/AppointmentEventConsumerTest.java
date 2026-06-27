package com.healthtech.notification.consumer;

import com.healthtech.notification.event.AppointmentBooked;
import com.healthtech.notification.event.AppointmentCancelled;
import com.healthtech.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class AppointmentEventConsumerTest {

    @Mock
    private NotificationService notificationService;

    private AppointmentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AppointmentEventConsumer(notificationService);
    }

    @Test
    void consumeBookedEvent_shouldDelegateToNotificationService() {
        // Arrange
        AppointmentBooked event = AppointmentBooked.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(UUID.randomUUID())
                .patientId(UUID.randomUUID())
                .doctorId(UUID.randomUUID())
                .duration(30)
                .dateTime(LocalDateTime.now().plusDays(1))
                .bookedAt(LocalDateTime.now())
                .build();

        // Act
        consumer.consumeBookedEvent(event);

        // Assert
        verify(notificationService).createForBookedAppointment(event);
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void consumeCancelledEvent_shouldDelegateToNotificationService() {
        // Arrange
        AppointmentCancelled event = AppointmentCancelled.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(UUID.randomUUID())
                .patientId(UUID.randomUUID())
                .doctorId(UUID.randomUUID())
                .duration(30)
                .dateTime(LocalDateTime.now().plusDays(1))
                .cancelledAt(LocalDateTime.now())
                .build();

        // Act
        consumer.consumeCancelledEvent(event);

        // Assert
        verify(notificationService).createForCancelledAppointment(event);
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void consumeBookedEvent_shouldNotTriggerCancelledFlow() {
        // Arrange
        AppointmentBooked event = AppointmentBooked.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(UUID.randomUUID())
                .patientId(UUID.randomUUID())
                .doctorId(UUID.randomUUID())
                .dateTime(LocalDateTime.now().plusDays(1))
                .build();

        // Act
        consumer.consumeBookedEvent(event);

        // Assert
        verify(notificationService).createForBookedAppointment(event);
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void consumeCancelledEvent_shouldNotTriggerBookedFlow() {
        // Arrange
        AppointmentCancelled event = AppointmentCancelled.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(UUID.randomUUID())
                .patientId(UUID.randomUUID())
                .doctorId(UUID.randomUUID())
                .dateTime(LocalDateTime.now().plusDays(1))
                .build();

        // Act
        consumer.consumeCancelledEvent(event);

        // Assert
        verify(notificationService).createForCancelledAppointment(event);
        verifyNoMoreInteractions(notificationService);
    }
}
