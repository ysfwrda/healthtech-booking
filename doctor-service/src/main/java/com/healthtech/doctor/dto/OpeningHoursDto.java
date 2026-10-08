package com.healthtech.doctor.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpeningHoursDto {
    @NotNull
    private DayOfWeek dayOfWeek;
    @NotNull
    @Schema(type = "string", example = "09:00", description = "Opening time, HH:mm")
    private LocalTime startTime;
    @NotNull
    @Schema(type = "string", example = "17:00", description = "Closing time, HH:mm, after startTime")
    private LocalTime endTime;
}
