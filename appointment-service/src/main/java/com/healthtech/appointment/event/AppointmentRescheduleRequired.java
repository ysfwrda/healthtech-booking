package com.healthtech.appointment.event;

import com.healthtech.appointment.domain.ConflictReason;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

// The doctor's availability changed so that this appointment no longer fits; the patient should move or cancel it.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentRescheduleRequired {
    private UUID eventId;
    private UUID appointmentId;
    private UUID patientId;
    private UUID doctorId;
    private Integer duration;
    private LocalDateTime dateTime;
    private ConflictReason reason;
    private LocalDateTime detectedAt;
}
