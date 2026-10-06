package com.healthtech.notification.event;

import com.healthtech.notification.domain.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentCancelled implements AppointmentNotificationEvent {
    private UUID eventId;
    private UUID appointmentId;
    private UUID patientId;
    private UUID doctorId;
    private Integer duration;
    private LocalDateTime dateTime;
    private LocalDateTime cancelledAt;

    @Override
    public NotificationType notificationType() {
        return NotificationType.APPOINTMENT_CANCELLED;
    }

    @Override
    public String notificationMessage() {
        return "Appointment cancelled for " + dateTime;
    }
}
