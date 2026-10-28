package com.healthtech.appointment.readmodel;

import jakarta.persistence.Embeddable;
import lombok.*;

import java.time.LocalDate;

// Whole days, both ends inclusive.
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VacationPeriod {
    private LocalDate startDate;
    private LocalDate endDate;

    public boolean covers(LocalDate day) {
        return !day.isBefore(startDate) && !day.isAfter(endDate);
    }
}
