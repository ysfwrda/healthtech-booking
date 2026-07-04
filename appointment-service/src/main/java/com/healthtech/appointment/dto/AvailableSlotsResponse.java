package com.healthtech.appointment.dto;

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

    private List<LocalDateTime> availableSlots;
}
