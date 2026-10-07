package com.healthtech.appointment.service;

import com.healthtech.appointment.readmodel.OpeningHours;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class SlotPolicyTest {

    private final SlotPolicy slotPolicy = new SlotPolicy();
    private static final LocalDate DATE = LocalDate.of(2030, 3, 18);

    @Test
    void isAligned_onTheHourAndHalfHour_shouldBeTrue() {
        // Act and Assert
        assertThat(slotPolicy.isAligned(LocalTime.of(9, 0))).isTrue();
        assertThat(slotPolicy.isAligned(LocalTime.of(9, 30))).isTrue();
        assertThat(slotPolicy.isAligned(LocalTime.MIDNIGHT)).isTrue();
    }

    @Test
    void isAligned_offGridMinuteSecondOrNano_shouldBeFalse() {
        // Act and Assert
        assertThat(slotPolicy.isAligned(LocalTime.of(9, 15))).isFalse();
        assertThat(slotPolicy.isAligned(LocalTime.of(9, 59))).isFalse();
        assertThat(slotPolicy.isAligned(LocalTime.of(9, 0, 1))).isFalse();
        assertThat(slotPolicy.isAligned(LocalTime.of(9, 30, 0, 1))).isFalse();
    }

    @Test
    void slotEnd_shouldBeThirtyMinutesAfterStart() {
        // Act and Assert
        assertThat(slotPolicy.slotEnd(LocalTime.of(16, 30))).isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    void slotsWithin_shouldStartAtOpeningAndEndOneSlotBeforeClosing() {
        // Arrange
        OpeningHours hours = OpeningHours.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(11, 0))
                .build();

        // Act and Assert
        assertThat(slotPolicy.slotsWithin(DATE, hours)).containsExactly(
                DATE.atTime(9, 0), DATE.atTime(9, 30), DATE.atTime(10, 0), DATE.atTime(10, 30));
    }

    @Test
    void slotsWithin_blockShorterThanOneSlot_shouldBeEmpty() {
        // Arrange
        OpeningHours hours = OpeningHours.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(9, 20))
                .build();

        // Act and Assert
        assertThat(slotPolicy.slotsWithin(DATE, hours)).isEmpty();
    }

    @Test
    void slotsWithin_offGridStart_shouldStartAtNextAlignedSlot() {
        // Arrange: a block stored before doctor-service rejected off-grid opening hours
        OpeningHours hours = OpeningHours.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 15))
                .endTime(LocalTime.of(11, 0))
                .build();

        // Act and Assert
        assertThat(slotPolicy.slotsWithin(DATE, hours)).containsExactly(
                DATE.atTime(9, 30), DATE.atTime(10, 0), DATE.atTime(10, 30));
    }

    @Test
    void slotsWithin_offGridSecondsInStart_shouldStartAtNextAlignedSlot() {
        // Arrange
        OpeningHours hours = OpeningHours.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0, 30))
                .endTime(LocalTime.of(10, 0))
                .build();

        // Act and Assert
        assertThat(slotPolicy.slotsWithin(DATE, hours)).containsExactly(DATE.atTime(9, 30));
    }

    @Test
    void slotsWithin_offGridEnd_shouldStopAtLastSlotEndingBeforeClosing() {
        // Arrange
        OpeningHours hours = OpeningHours.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(10, 45))
                .build();

        // Act and Assert
        assertThat(slotPolicy.slotsWithin(DATE, hours)).containsExactly(
                DATE.atTime(9, 0), DATE.atTime(9, 30), DATE.atTime(10, 0));
    }

    @Test
    void slotsWithin_blockEndingJustAfterMidnight_shouldBeEmpty() {
        // Arrange: endTime minus one slot would wrap to 23:40 if computed on LocalTime
        OpeningHours hours = OpeningHours.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.MIDNIGHT)
                .endTime(LocalTime.of(0, 10))
                .build();

        // Act and Assert
        assertThat(slotPolicy.slotsWithin(DATE, hours)).isEmpty();
    }

    @Test
    void slotsWithin_offGridStartInLastHalfHour_shouldNotRollIntoNextDay() {
        // Arrange: rounding 23:45 up to the grid lands on the next day's midnight
        OpeningHours hours = OpeningHours.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(23, 45))
                .endTime(LocalTime.of(23, 59))
                .build();

        // Act and Assert
        assertThat(slotPolicy.slotsWithin(DATE, hours)).isEmpty();
    }
}
