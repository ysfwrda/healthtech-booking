package com.healthtech.doctor.dto;

import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VacationDto {
    private UUID id;
    private LocalDate startDate;
    private LocalDate endDate;
}
