package com.healthtech.appointment.readmodel;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
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
        // Arrange
        OpeningHours block = hours(DayOfWeek.MONDAY, 9, 17);

        // Act and Assert
        assertThat(block.covers(LocalTime.of(9, 0), LocalTime.of(9, 30))).isTrue();
        assertThat(block.covers(LocalTime.of(16, 30), LocalTime.of(17, 0))).isTrue();
        assertThat(block.covers(LocalTime.of(8, 30), LocalTime.of(9, 0))).isFalse();
        assertThat(block.covers(LocalTime.of(16, 45), LocalTime.of(17, 15))).isFalse();
    }

    @Test
    void openingHoursOn_shouldReturnOnlyThatDaysBlocks() {
        // Arrange: the doctor fixture (Monday 9-12 and 14-17, Tuesday 8-10)

        // Act and Assert: the read-model OpeningHours has no equals(), so compare field by field.
        assertThat(doctor.openingHoursOn(DayOfWeek.MONDAY))
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrder(hours(DayOfWeek.MONDAY, 9, 12), hours(DayOfWeek.MONDAY, 14, 17));
        assertThat(doctor.openingHoursOn(DayOfWeek.WEDNESDAY)).isEmpty();
    }

    @Test
    void isOpenFor_slotInsideAnyBlockOfThatDay_shouldBeTrue() {
        // Arrange: the doctor fixture

        // Act and Assert
        assertThat(doctor.isOpenFor(DayOfWeek.MONDAY, LocalTime.of(11, 30), LocalTime.of(12, 0))).isTrue();
        assertThat(doctor.isOpenFor(DayOfWeek.MONDAY, LocalTime.of(14, 0), LocalTime.of(14, 30))).isTrue();
    }

    @Test
    void isOpenFor_slotInLunchGapOrOnAnotherDay_shouldBeFalse() {
        // Arrange: the doctor fixture

        // Act and Assert
        assertThat(doctor.isOpenFor(DayOfWeek.MONDAY, LocalTime.of(12, 0), LocalTime.of(12, 30))).isFalse();
        assertThat(doctor.isOpenFor(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0), LocalTime.of(9, 30))).isFalse();
        assertThat(doctor.isOpenFor(DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(10, 30))).isFalse();
    }

    private static VacationPeriod vacation(LocalDate start, LocalDate end) {
        return VacationPeriod.builder().startDate(start).endDate(end).build();
    }

    @Test
    void isOnVacation_shouldIncludeBothEndDaysAndExcludeTheDaysAround() {
        // Arrange
        ValidDoctor onVacation = ValidDoctor.builder()
                .vacations(Set.of(vacation(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 26))))
                .build();

        // Act & Assert
        assertThat(onVacation.isOnVacation(LocalDate.of(2026, 12, 23))).isFalse();
        assertThat(onVacation.isOnVacation(LocalDate.of(2026, 12, 24))).isTrue();
        assertThat(onVacation.isOnVacation(LocalDate.of(2026, 12, 25))).isTrue();
        assertThat(onVacation.isOnVacation(LocalDate.of(2026, 12, 26))).isTrue();
        assertThat(onVacation.isOnVacation(LocalDate.of(2026, 12, 27))).isFalse();
    }

    @Test
    void isOnVacation_singleDayVacation_coversOnlyThatDay() {
        // Arrange
        ValidDoctor onVacation = ValidDoctor.builder()
                .vacations(Set.of(vacation(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1))))
                .build();

        // Act & Assert
        assertThat(onVacation.isOnVacation(LocalDate.of(2026, 5, 1))).isTrue();
        assertThat(onVacation.isOnVacation(LocalDate.of(2026, 5, 2))).isFalse();
    }

    @Test
    void isOnVacation_noVacations_isFalse() {
        // Arrange
        ValidDoctor noVacation = ValidDoctor.builder().build();

        // Act & Assert
        assertThat(noVacation.isOnVacation(LocalDate.of(2026, 5, 1))).isFalse();
    }
}
