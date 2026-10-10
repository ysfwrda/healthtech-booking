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
public class AppointmentChanged implements AppointmentNotificationEvent {
    private UUID eventId;
    private UUID appointmentId;
    private UUID patientId;
    private UUID doctorId;
    private Integer duration;
    private String type;
    private LocalDateTime dateTime;
    private String previousType;
    private LocalDateTime previousDateTime;
    private LocalDateTime changedAt;

    @Override
    public NotificationType notificationType() {
        return NotificationType.APPOINTMENT_CHANGED;
    }
}
