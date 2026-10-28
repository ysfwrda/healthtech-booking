package com.healthtech.appointment.dto;

import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.domain.ConflictReason;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentResponse {
    private UUID id;

    private UUID patientId;

    private UUID doctorId;

    private LocalDateTime dateTime;

    private Integer duration;

    private AppointmentType type;

    private AppointmentStatus status;

    private ConflictReason conflictReason;

    private String notes;

    private LocalDateTime createdAt;
}
