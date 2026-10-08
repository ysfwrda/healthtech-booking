package com.healthtech.appointment.service.booking;

import com.healthtech.appointment.exception.OutsideOpeningHoursException;
import com.healthtech.appointment.exception.SlotInPastException;
import com.healthtech.appointment.exception.SlotNotAlignedException;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.service.SlotPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingRulesTest {

    // 2030-03-18 is a Monday.
    private static final LocalDate MONDAY = LocalDate.of(2030, 3, 18);

    private final SlotPolicy slotPolicy = new SlotPolicy();
    private final SlotAlignedRule slotAligned = new SlotAlignedRule(slotPolicy);
    private final WithinOpeningHoursRule withinOpeningHours = new WithinOpeningHoursRule(slotPolicy);

    // "Now" is Monday 2030-03-18 12:00, so earlier that day is past and later is future.
    private static final LocalDateTime NOW = MONDAY.atTime(12, 0);
    private final NotInPastRule notInPast =
            new NotInPastRule(Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

    private final ValidDoctor mondayNineToFive = ValidDoctor.builder()
            .openingHours(Set.of(OpeningHours.builder()
                    .dayOfWeek(DayOfWeek.MONDAY)
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(17, 0))
                    .build()))
            .build();

    @Test
    void slotAligned_onGrid_shouldPass() {
        // Arrange: the Monday 9-17 doctor fixture

        // Act and Assert
        assertThatCode(() -> slotAligned.check(MONDAY.atTime(10, 30), mondayNineToFive)).doesNotThrowAnyException();
    }

    @Test
    void slotAligned_offGrid_shouldThrowSlotNotAligned() {
        // Arrange: the Monday 9-17 doctor fixture

        // Act and Assert
        assertThatThrownBy(() -> slotAligned.check(MONDAY.atTime(10, 15), mondayNineToFive))
                .isInstanceOf(SlotNotAlignedException.class);
        assertThatThrownBy(() -> slotAligned.check(MONDAY.atTime(10, 0, 30), mondayNineToFive))
                .isInstanceOf(SlotNotAlignedException.class);
    }

    @Test
    void withinOpeningHours_lastSlotEndingAtClosing_shouldPass() {
        // Arrange: the Monday 9-17 doctor fixture

        // Act and Assert
        assertThatCode(() -> withinOpeningHours.check(MONDAY.atTime(16, 30), mondayNineToFive))
                .doesNotThrowAnyException();
    }

    @Test
    void withinOpeningHours_atClosingOrOnClosedDay_shouldThrowOutsideOpeningHours() {
        // Arrange: the Monday 9-17 doctor fixture

        // Act and Assert
        assertThatThrownBy(() -> withinOpeningHours.check(MONDAY.atTime(17, 0), mondayNineToFive))
                .isInstanceOf(OutsideOpeningHoursException.class);
        assertThatThrownBy(() -> withinOpeningHours.check(MONDAY.plusDays(1).atTime(10, 0), mondayNineToFive))
                .isInstanceOf(OutsideOpeningHoursException.class);
    }

    @Test
    void notInPast_slotBeforeNow_shouldThrowSlotInPast() {
        // Arrange: now is 12:00

        // Act and Assert
        assertThatThrownBy(() -> notInPast.check(MONDAY.atTime(11, 30), mondayNineToFive))
                .isInstanceOf(SlotInPastException.class);
        assertThatThrownBy(() -> notInPast.check(MONDAY.minusDays(7).atTime(12, 0), mondayNineToFive))
                .isInstanceOf(SlotInPastException.class);
    }

    @Test
    void notInPast_slotExactlyNowOrLater_shouldPass() {
        // Arrange: now is 12:00; a slot starting exactly now is kept, as in availability

        // Act and Assert
        assertThatCode(() -> notInPast.check(NOW, mondayNineToFive)).doesNotThrowAnyException();
        assertThatCode(() -> notInPast.check(MONDAY.atTime(12, 30), mondayNineToFive)).doesNotThrowAnyException();
        assertThatCode(() -> notInPast.check(MONDAY.plusDays(7).atTime(9, 0), mondayNineToFive))
                .doesNotThrowAnyException();
    }

    @Test
    void ruleOrder_shouldCheckAlignmentThenOpeningHoursThenNotInPast() {
        // Spring injects List<BookingRule> sorted by @Order; sorting the same way here pins
        // which error wins for a slot that fails several (e.g. 07:15): SlotNotAligned.
        // Arrange
        List<BookingRule> rules = new ArrayList<>(List.of(notInPast, withinOpeningHours, slotAligned));
        LocalDateTime failsBoth = MONDAY.atTime(7, 15);

        // Act
        AnnotationAwareOrderComparator.sort(rules);

        // Assert
        assertThat(rules).containsExactly(slotAligned, withinOpeningHours, notInPast);
        assertThatThrownBy(() -> rules.forEach(rule -> rule.check(failsBoth, mondayNineToFive)))
                .isInstanceOf(SlotNotAlignedException.class);
        // Aligned but outside hours and in the past: opening hours wins over past.
        LocalDateTime pastAndOutsideHours = MONDAY.atTime(7, 0);
        assertThatThrownBy(() -> rules.forEach(rule -> rule.check(pastAndOutsideHours, mondayNineToFive)))
                .isInstanceOf(OutsideOpeningHoursException.class);
    }
}
