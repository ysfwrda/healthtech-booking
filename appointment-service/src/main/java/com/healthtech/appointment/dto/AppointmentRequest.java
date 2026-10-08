package com.healthtech.appointment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.healthtech.appointment.domain.AppointmentType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentRequest {
    @NotNull
    @Schema(description = "Id of an existing doctor, taken from GET /api/doctors", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID doctorId;

    @NotNull
    @Schema(description = "Start of a free slot, taken from GET /api/availability", example = "2026-10-12T09:00:00")
    private LocalDateTime dateTime;

    @NotNull
    private AppointmentType type;

    @Size(max = 500)
    @Schema(example = "First visit, recurring back pain")
    private String notes;
}
