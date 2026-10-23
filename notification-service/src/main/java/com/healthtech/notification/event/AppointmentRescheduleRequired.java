package com.healthtech.notification.event;

import com.healthtech.notification.domain.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

// The doctor's availability changed so that this appointment no longer fits. reason is OUTSIDE_OPENING_HOURS or
// DOCTOR_VACATION; the notification text is the same for both.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentRescheduleRequired implements AppointmentNotificationEvent {
    private UUID eventId;
    private UUID appointmentId;
    private UUID patientId;
    private UUID doctorId;
    private Integer duration;
    private LocalDateTime dateTime;
    private String reason;
    private LocalDateTime detectedAt;

    @Override
    public NotificationType notificationType() {
        return NotificationType.APPOINTMENT_RESCHEDULE_REQUIRED;
    }
}
