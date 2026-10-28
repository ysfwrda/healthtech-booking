package com.healthtech.doctor.dto;

import lombok.*;

import java.util.List;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AvailabilityResponse {
    private Set<OpeningHoursDto> openingHours;
    // Vacations that have not ended yet, earliest first.
    private List<VacationDto> vacations;
}
