package com.healthtech.notification.event;

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
public class AppointmentCancelled {
    private UUID eventId;
    private UUID appointmentId;
    private UUID patientId;
    private UUID doctorId;
    private Integer duration;
    private LocalDateTime dateTime;
    private LocalDateTime cancelledAt;
}
