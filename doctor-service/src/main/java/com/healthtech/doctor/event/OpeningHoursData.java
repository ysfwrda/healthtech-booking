package com.healthtech.doctor.event;

import com.healthtech.doctor.domain.OpeningHours;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpeningHoursData {
    private DayOfWeek dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;

    public static OpeningHoursData from(OpeningHours openingHours) {
        return OpeningHoursData.builder()
                .dayOfWeek(openingHours.getDayOfWeek())
                .startTime(openingHours.getStartTime())
                .endTime(openingHours.getEndTime())
                .build();
    }

    // A doctor registered without opening hours publishes an empty set, never null.
    public static Set<OpeningHoursData> fromAll(Set<OpeningHours> openingHours) {
        return openingHours == null ? Set.of() :
                openingHours.stream().map(OpeningHoursData::from).collect(Collectors.toSet());
    }
}
