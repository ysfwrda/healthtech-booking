package com.healthtech.appointment.event;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentConfirmed {
    private UUID eventId;
    private UUID appointmentId;
    private UUID patientId;
    private UUID doctorId;
    private Integer duration;
    private LocalDateTime dateTime;
    private LocalDateTime confirmedAt;
}
