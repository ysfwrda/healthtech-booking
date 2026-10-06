package com.healthtech.appointment.service.booking;

import com.healthtech.appointment.exception.OutsideOpeningHoursException;
import com.healthtech.appointment.exception.SlotNotAlignedException;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.service.SlotPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    private final ValidDoctor mondayNineToFive = ValidDoctor.builder()
            .openingHours(Set.of(OpeningHours.builder()
                    .dayOfWeek(DayOfWeek.MONDAY)
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(17, 0))
                    .build()))
            .build();

    @Test
    void slotAligned_onGrid_shouldPass() {
        assertThatCode(() -> slotAligned.check(MONDAY.atTime(10, 30), mondayNineToFive)).doesNotThrowAnyException();
    }

    @Test
    void slotAligned_offGrid_shouldThrowSlotNotAligned() {
        assertThatThrownBy(() -> slotAligned.check(MONDAY.atTime(10, 15), mondayNineToFive))
                .isInstanceOf(SlotNotAlignedException.class);
        assertThatThrownBy(() -> slotAligned.check(MONDAY.atTime(10, 0, 30), mondayNineToFive))
                .isInstanceOf(SlotNotAlignedException.class);
    }

    @Test
    void withinOpeningHours_lastSlotEndingAtClosing_shouldPass() {
        assertThatCode(() -> withinOpeningHours.check(MONDAY.atTime(16, 30), mondayNineToFive))
                .doesNotThrowAnyException();
    }

    @Test
    void withinOpeningHours_atClosingOrOnClosedDay_shouldThrowOutsideOpeningHours() {
        assertThatThrownBy(() -> withinOpeningHours.check(MONDAY.atTime(17, 0), mondayNineToFive))
                .isInstanceOf(OutsideOpeningHoursException.class);
        assertThatThrownBy(() -> withinOpeningHours.check(MONDAY.plusDays(1).atTime(10, 0), mondayNineToFive))
                .isInstanceOf(OutsideOpeningHoursException.class);
    }

    @Test
    void ruleOrder_shouldCheckAlignmentBeforeOpeningHours() {
        // Spring injects List<BookingRule> sorted by @Order; sorting the same way here pins
        // which error wins for a slot that fails both (e.g. 07:15): SlotNotAligned.
        List<BookingRule> rules = new ArrayList<>(List.of(withinOpeningHours, slotAligned));
        AnnotationAwareOrderComparator.sort(rules);

        assertThat(rules).containsExactly(slotAligned, withinOpeningHours);
        LocalDateTime failsBoth = MONDAY.atTime(7, 15);
        assertThatThrownBy(() -> rules.forEach(rule -> rule.check(failsBoth, mondayNineToFive)))
                .isInstanceOf(SlotNotAlignedException.class);
    }
}
