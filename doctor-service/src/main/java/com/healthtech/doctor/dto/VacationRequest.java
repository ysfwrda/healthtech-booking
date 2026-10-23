package com.healthtech.doctor.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VacationRequest {
    @NotNull
    @Schema(type = "string", format = "date", example = "2026-12-24", description = "First day off, inclusive")
    private LocalDate startDate;

    @NotNull
    @Schema(type = "string", format = "date", example = "2026-12-31",
            description = "Last day off, inclusive; not before startDate and not in the past")
    private LocalDate endDate;
}
