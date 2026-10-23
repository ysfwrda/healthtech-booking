package com.healthtech.doctor.event;

import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VacationData {
    private LocalDate startDate;
    private LocalDate endDate;
}
