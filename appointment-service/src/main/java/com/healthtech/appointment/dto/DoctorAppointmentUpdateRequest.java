package com.healthtech.appointment.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.healthtech.appointment.domain.AppointmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import lombok.*;

import java.time.LocalDateTime;

// No notes: they belong to the patient, and every accepted doctor change is one the patient is told about.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoctorAppointmentUpdateRequest {
    @Schema(description = "New purpose; omit to keep the current one")
    private AppointmentType type;

    @Schema(description = "Start of a free slot inside the doctor's availability; omit to keep the current time", example = "2026-10-12T09:30:00")
    private LocalDateTime dateTime;

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "at least one of type or dateTime is required")
    public boolean isAnyFieldPresent() {
        return type != null || dateTime != null;
    }
}
