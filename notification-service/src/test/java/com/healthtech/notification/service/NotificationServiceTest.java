package com.healthtech.notification.service;

import com.healthtech.notification.domain.Notification;
import com.healthtech.notification.domain.NotificationType;
import com.healthtech.notification.event.AppointmentBooked;
import com.healthtech.notification.event.AppointmentCancelled;
import com.healthtech.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

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

        notificationService.createForBookedAppointment(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        Notification saved = captor.getValue();
        assertThat(saved.getAppointmentId()).isEqualTo(appointmentId);
        assertThat(saved.getPatientId()).isEqualTo(patientId);
        assertThat(saved.getDoctorId()).isEqualTo(doctorId);
        assertThat(saved.getType()).isEqualTo(NotificationType.APPOINTMENT_BOOKED);
        assertThat(saved.getMessage()).contains(dateTime.toString());
    }

    @Test
    void createForCancelledAppointment_shouldSaveNotificationWithCancelledType() {
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

        notificationService.createForCancelledAppointment(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        Notification saved = captor.getValue();
        assertThat(saved.getAppointmentId()).isEqualTo(appointmentId);
        assertThat(saved.getPatientId()).isEqualTo(patientId);
        assertThat(saved.getDoctorId()).isEqualTo(doctorId);
        assertThat(saved.getType()).isEqualTo(NotificationType.APPOINTMENT_CANCELLED);
        assertThat(saved.getMessage()).contains(dateTime.toString());
    }

    @Test
    void createForBookedAppointment_shouldNotSetCancelledType() {
        AppointmentBooked event = AppointmentBooked.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(UUID.randomUUID())
                .patientId(UUID.randomUUID())
                .doctorId(UUID.randomUUID())
                .dateTime(LocalDateTime.now().plusDays(3))
                .build();

        notificationService.createForBookedAppointment(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isNotEqualTo(NotificationType.APPOINTMENT_CANCELLED);
    }

    @Test
    void createForCancelledAppointment_shouldNotSetBookedType() {
        AppointmentCancelled event = AppointmentCancelled.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(UUID.randomUUID())
                .patientId(UUID.randomUUID())
                .doctorId(UUID.randomUUID())
                .dateTime(LocalDateTime.now().plusDays(3))
                .build();

        notificationService.createForCancelledAppointment(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isNotEqualTo(NotificationType.APPOINTMENT_BOOKED);
    }
}
