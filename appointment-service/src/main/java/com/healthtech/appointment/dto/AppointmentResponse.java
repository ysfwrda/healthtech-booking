package com.healthtech.appointment.dto;

import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.AppointmentType;
import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "Start of the appointment in clinic time (Europe/Berlin)", example = "2026-10-12T09:00:00")
    private LocalDateTime dateTime;

    private Integer duration;

    private AppointmentType type;

    private AppointmentStatus status;

    private String notes;

    private LocalDateTime createdAt;
}
