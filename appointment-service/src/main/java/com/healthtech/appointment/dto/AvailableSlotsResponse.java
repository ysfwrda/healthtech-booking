package com.healthtech.appointment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AvailableSlotsResponse {
    private UUID doctorId;

    private LocalDate date;

    @Schema(description = "Available 30-minute slot start times in clinic time (Europe/Berlin)")
    private List<LocalDateTime> availableSlots;
}

