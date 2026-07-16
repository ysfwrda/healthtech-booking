package com.healthtech.doctor.domain;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpeningHoursTest {

    private OpeningHours monday9to17() {
        return OpeningHours.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .build();
    }

    @Test
    void equalFieldValues_areEqual_andShareHashCode() {
        OpeningHours a = monday9to17();
        OpeningHours b = monday9to17();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void differingDayOfWeek_isNotEqual() {
        OpeningHours a = monday9to17();
        OpeningHours b = OpeningHours.builder()
                .dayOfWeek(DayOfWeek.TUESDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differingStartTime_isNotEqual() {
        OpeningHours a = monday9to17();
        OpeningHours b = OpeningHours.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(17, 0))
                .build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differingEndTime_isNotEqual() {
        OpeningHours a = monday9to17();
        OpeningHours b = OpeningHours.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void setOfIdenticalBlocks_collapsesToOneEntry() {
        Set<OpeningHours> blocks = new HashSet<>();
        blocks.add(monday9to17());
        blocks.add(monday9to17());

        assertThat(blocks).hasSize(1);
    }
}
