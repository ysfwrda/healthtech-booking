package com.healthtech.appointment.event;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentBooked {
    private UUID eventId;
    private UUID appointmentId;
    private UUID patientId;
    private String patientName;
    private UUID doctorId;
    private String doctorName;
    private Integer duration;
    private LocalDateTime dateTime;
    private LocalDateTime bookedAt;
}