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
        assertThat(slotPolicy.isAligned(LocalTime.of(9, 0))).isTrue();
        assertThat(slotPolicy.isAligned(LocalTime.of(9, 30))).isTrue();
        assertThat(slotPolicy.isAligned(LocalTime.MIDNIGHT)).isTrue();
    }

    @Test
    void isAligned_offGridMinuteSecondOrNano_shouldBeFalse() {
        assertThat(slotPolicy.isAligned(LocalTime.of(9, 15))).isFalse();
        assertThat(slotPolicy.isAligned(LocalTime.of(9, 59))).isFalse();
        assertThat(slotPolicy.isAligned(LocalTime.of(9, 0, 1))).isFalse();
        assertThat(slotPolicy.isAligned(LocalTime.of(9, 30, 0, 1))).isFalse();
    }

    @Test
    void slotEnd_shouldBeThirtyMinutesAfterStart() {
        assertThat(slotPolicy.slotEnd(LocalTime.of(16, 30))).isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    void slotsWithin_shouldStartAtOpeningAndEndOneSlotBeforeClosing() {
        OpeningHours hours = OpeningHours.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(11, 0))
                .build();

        assertThat(slotPolicy.slotsWithin(DATE, hours)).containsExactly(
                DATE.atTime(9, 0), DATE.atTime(9, 30), DATE.atTime(10, 0), DATE.atTime(10, 30));
    }

    @Test
    void slotsWithin_blockShorterThanOneSlot_shouldBeEmpty() {
        OpeningHours hours = OpeningHours.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(9, 20))
                .build();

        assertThat(slotPolicy.slotsWithin(DATE, hours)).isEmpty();
    }
}
