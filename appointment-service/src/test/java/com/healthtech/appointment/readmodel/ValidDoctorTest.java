package com.healthtech.appointment.readmodel;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ValidDoctorTest {

    private static OpeningHours hours(DayOfWeek day, int startHour, int endHour) {
        return OpeningHours.builder()
                .dayOfWeek(day)
                .startTime(LocalTime.of(startHour, 0))
                .endTime(LocalTime.of(endHour, 0))
                .build();
    }

    private final ValidDoctor doctor = ValidDoctor.builder()
            .openingHours(Set.of(
                    hours(DayOfWeek.MONDAY, 9, 12),
                    hours(DayOfWeek.MONDAY, 14, 17),
                    hours(DayOfWeek.TUESDAY, 8, 10)))
            .build();

    @Test
    void openingHoursCovers_shouldIncludeBothBoundaries() {
        OpeningHours block = hours(DayOfWeek.MONDAY, 9, 17);

        assertThat(block.covers(LocalTime.of(9, 0), LocalTime.of(9, 30))).isTrue();
        assertThat(block.covers(LocalTime.of(16, 30), LocalTime.of(17, 0))).isTrue();
        assertThat(block.covers(LocalTime.of(8, 30), LocalTime.of(9, 0))).isFalse();
        assertThat(block.covers(LocalTime.of(16, 45), LocalTime.of(17, 15))).isFalse();
    }

    @Test
    void openingHoursOn_shouldReturnOnlyThatDaysBlocks() {
        // The read-model OpeningHours has no equals(), so compare field by field.
        assertThat(doctor.openingHoursOn(DayOfWeek.MONDAY))
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrder(hours(DayOfWeek.MONDAY, 9, 12), hours(DayOfWeek.MONDAY, 14, 17));
        assertThat(doctor.openingHoursOn(DayOfWeek.WEDNESDAY)).isEmpty();
    }

    @Test
    void isOpenFor_slotInsideAnyBlockOfThatDay_shouldBeTrue() {
        assertThat(doctor.isOpenFor(DayOfWeek.MONDAY, LocalTime.of(11, 30), LocalTime.of(12, 0))).isTrue();
        assertThat(doctor.isOpenFor(DayOfWeek.MONDAY, LocalTime.of(14, 0), LocalTime.of(14, 30))).isTrue();
    }

    @Test
    void isOpenFor_slotInLunchGapOrOnAnotherDay_shouldBeFalse() {
        assertThat(doctor.isOpenFor(DayOfWeek.MONDAY, LocalTime.of(12, 0), LocalTime.of(12, 30))).isFalse();
        assertThat(doctor.isOpenFor(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0), LocalTime.of(9, 30))).isFalse();
        assertThat(doctor.isOpenFor(DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(10, 30))).isFalse();
    }
}
