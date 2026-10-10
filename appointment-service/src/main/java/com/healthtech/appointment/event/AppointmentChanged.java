package com.healthtech.appointment.event;

import com.healthtech.appointment.domain.AppointmentType;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentChanged {
    private UUID eventId;
    private UUID appointmentId;
    private UUID patientId;
    private UUID doctorId;
    private Integer duration;
    private AppointmentType type;
    private LocalDateTime dateTime;
    private AppointmentType previousType;
    private LocalDateTime previousDateTime;
    private LocalDateTime changedAt;
}
