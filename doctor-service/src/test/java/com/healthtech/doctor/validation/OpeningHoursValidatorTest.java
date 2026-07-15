package com.healthtech.doctor.validation;

import com.healthtech.doctor.dto.OpeningHoursDto;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

class OpeningHoursValidatorTest {

    private final OpeningHoursValidator validator = new OpeningHoursValidator();
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);
    }

    private static OpeningHoursDto block(DayOfWeek day, int startHour, int startMinute, int endHour, int endMinute) {
        return OpeningHoursDto.builder()
                .dayOfWeek(day)
                .startTime(LocalTime.of(startHour, startMinute))
                .endTime(LocalTime.of(endHour, endMinute))
                .build();
    }

    @Test
    void overlappingBlocksSameDay_areInvalid() {
        Set<OpeningHoursDto> blocks = new LinkedHashSet<>(Set.of(
                block(DayOfWeek.MONDAY, 9, 0, 13, 0),
                block(DayOfWeek.MONDAY, 12, 0, 17, 0)
        ));

        assertThat(validator.isValid(blocks, context)).isFalse();
    }

    @Test
    void backToBackBlocksSameDay_areValid() {
        Set<OpeningHoursDto> blocks = new LinkedHashSet<>(Set.of(
                block(DayOfWeek.MONDAY, 8, 0, 12, 0),
                block(DayOfWeek.MONDAY, 12, 0, 16, 0)
        ));

        assertThat(validator.isValid(blocks, context)).isTrue();
    }

    @Test
    void identicalTimesOnDifferentDays_areValid() {
        Set<OpeningHoursDto> blocks = new LinkedHashSet<>(Set.of(
                block(DayOfWeek.MONDAY, 9, 0, 17, 0),
                block(DayOfWeek.TUESDAY, 9, 0, 17, 0)
        ));

        assertThat(validator.isValid(blocks, context)).isTrue();
    }

    @Test
    void singleBlock_isValid() {
        Set<OpeningHoursDto> blocks = Set.of(block(DayOfWeek.MONDAY, 9, 0, 17, 0));

        assertThat(validator.isValid(blocks, context)).isTrue();
    }

    @Test
    void emptySet_isValid() {
        assertThat(validator.isValid(Set.of(), context)).isTrue();
    }

    @Test
    void nullSet_isValid() {
        assertThat(validator.isValid(null, context)).isTrue();
    }

    @Test
    void startTimeEqualsEndTime_isInvalid() {
        Set<OpeningHoursDto> blocks = Set.of(block(DayOfWeek.MONDAY, 9, 0, 9, 0));

        assertThat(validator.isValid(blocks, context)).isFalse();
    }

    @Test
    void startTimeAfterEndTime_isInvalid() {
        Set<OpeningHoursDto> blocks = Set.of(block(DayOfWeek.MONDAY, 17, 0, 9, 0));

        assertThat(validator.isValid(blocks, context)).isFalse();
    }

    @Test
    void threeBlocksOneDay_onlyOnePairOverlaps_isInvalid() {
        Set<OpeningHoursDto> blocks = new LinkedHashSet<>(Set.of(
                block(DayOfWeek.MONDAY, 8, 0, 10, 0),
                block(DayOfWeek.MONDAY, 9, 0, 11, 0),
                block(DayOfWeek.MONDAY, 14, 0, 16, 0)
        ));

        assertThat(validator.isValid(blocks, context)).isFalse();
    }
}
