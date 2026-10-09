package com.healthtech.appointment.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.healthtech.appointment.domain.AppointmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentUpdateRequest {
    @Schema(description = "New purpose; omit to keep the current one")
    private AppointmentType type;

    @Schema(description = "Start of a free slot of the same doctor, taken from GET /api/availability; omit to keep the current time", example = "2026-10-12T09:30:00")
    private LocalDateTime dateTime;

    @Size(max = 500)
    @Schema(description = "New notes; omit to keep the current ones, send an empty string to clear them", example = "Follow-up on the back pain")
    private String notes;

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "at least one of type, dateTime or notes is required")
    public boolean isAnyFieldPresent() {
        return type != null || dateTime != null || notes != null;
    }
}
