package com.healthtech.appointment.event;

import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VacationPayload {
    private LocalDate startDate;
    private LocalDate endDate;
}
