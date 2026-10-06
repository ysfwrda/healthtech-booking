package com.healthtech.appointment.readmodel;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
    @Enumerated(EnumType.STRING)
    private DayOfWeek dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;

    // True when [start, end] lies entirely inside this block: start >= startTime and
    // end <= endTime. A slot ending exactly at closing time fits.
    public boolean covers(LocalTime start, LocalTime end) {
        return !startTime.isAfter(start) && !endTime.isBefore(end);
    }
}
