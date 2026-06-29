package com.healthtech.doctor.domain;

import jakarta.persistence.Embeddable;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalTime;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpeningHours {
    private DayOfWeek dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
}
