package com.healthtech.appointment.dto;

import com.healthtech.appointment.domain.AppointmentType;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentRequest {
    private UUID patientId;

    private UUID doctorId;

    private LocalDateTime dateTime;

    private Integer duration;

    private AppointmentType type;

    private String notes;
}
