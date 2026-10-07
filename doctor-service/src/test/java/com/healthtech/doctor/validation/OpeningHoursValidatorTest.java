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
import static org.mockito.Mockito.verify;

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
    void identicalBlocksSameDay_areInvalid() {
        // Arrange: OpeningHoursDto has no equals()/hashCode(), so a Set never collapses these into
        // one element -- the overlap check is what actually catches an exact duplicate today.
        Set<OpeningHoursDto> blocks = new LinkedHashSet<>(Set.of(
                block(DayOfWeek.MONDAY, 9, 0, 17, 0),
                block(DayOfWeek.MONDAY, 9, 0, 17, 0)
        ));

        // Act and Assert
        assertThat(validator.isValid(blocks, context)).isFalse();
    }

    @Test
    void overlappingBlocksSameDay_areInvalid() {
        // Arrange
        Set<OpeningHoursDto> blocks = new LinkedHashSet<>(Set.of(
                block(DayOfWeek.MONDAY, 9, 0, 13, 0),
                block(DayOfWeek.MONDAY, 12, 0, 17, 0)
        ));

        // Act and Assert
        assertThat(validator.isValid(blocks, context)).isFalse();
    }

    @Test
    void backToBackBlocksSameDay_areValid() {
        // Arrange
        Set<OpeningHoursDto> blocks = new LinkedHashSet<>(Set.of(
                block(DayOfWeek.MONDAY, 8, 0, 12, 0),
                block(DayOfWeek.MONDAY, 12, 0, 16, 0)
        ));

        // Act and Assert
        assertThat(validator.isValid(blocks, context)).isTrue();
    }

    @Test
    void identicalTimesOnDifferentDays_areValid() {
        // Arrange
        Set<OpeningHoursDto> blocks = new LinkedHashSet<>(Set.of(
                block(DayOfWeek.MONDAY, 9, 0, 17, 0),
                block(DayOfWeek.TUESDAY, 9, 0, 17, 0)
        ));

        // Act and Assert
        assertThat(validator.isValid(blocks, context)).isTrue();
    }

    @Test
    void singleBlock_isValid() {
        // Arrange
        Set<OpeningHoursDto> blocks = Set.of(block(DayOfWeek.MONDAY, 9, 0, 17, 0));

        // Act and Assert
        assertThat(validator.isValid(blocks, context)).isTrue();
    }

    @Test
    void emptySet_isValid() {
        // Act and Assert
        assertThat(validator.isValid(Set.of(), context)).isTrue();
    }

    @Test
    void nullSet_isValid() {
        // Act and Assert
        assertThat(validator.isValid(null, context)).isTrue();
    }

    @Test
    void startTimeEqualsEndTime_isInvalid() {
        // Arrange
        Set<OpeningHoursDto> blocks = Set.of(block(DayOfWeek.MONDAY, 9, 0, 9, 0));

        // Act and Assert
        assertThat(validator.isValid(blocks, context)).isFalse();
    }

    @Test
    void startTimeAfterEndTime_isInvalid() {
        // Arrange
        Set<OpeningHoursDto> blocks = Set.of(block(DayOfWeek.MONDAY, 17, 0, 9, 0));

        // Act and Assert
        assertThat(validator.isValid(blocks, context)).isFalse();
    }

    @Test
    void threeBlocksOneDay_onlyOnePairOverlaps_isInvalid() {
        // Arrange
        Set<OpeningHoursDto> blocks = new LinkedHashSet<>(Set.of(
                block(DayOfWeek.MONDAY, 8, 0, 10, 0),
                block(DayOfWeek.MONDAY, 9, 0, 11, 0),
                block(DayOfWeek.MONDAY, 14, 0, 16, 0)
        ));

        // Act and Assert
        assertThat(validator.isValid(blocks, context)).isFalse();
    }

    @Test
    void startOffHalfHourGrid_isInvalid() {
        // Arrange
        Set<OpeningHoursDto> blocks = Set.of(block(DayOfWeek.MONDAY, 9, 15, 12, 0));

        // Act and Assert
        assertThat(validator.isValid(blocks, context)).isFalse();
        verify(context).buildConstraintViolationWithTemplate(
                "Opening hours for MONDAY must start and end on the hour or half hour");
    }

    @Test
    void endOffHalfHourGrid_isInvalid() {
        // Arrange
        Set<OpeningHoursDto> blocks = Set.of(block(DayOfWeek.MONDAY, 9, 0, 12, 45));

        // Act and Assert
        assertThat(validator.isValid(blocks, context)).isFalse();
    }

    @Test
    void secondsComponent_isInvalid() {
        // Arrange
        Set<OpeningHoursDto> blocks = Set.of(OpeningHoursDto.builder()
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0, 30))
                .endTime(LocalTime.of(12, 0))
                .build());

        // Act and Assert
        assertThat(validator.isValid(blocks, context)).isFalse();
    }

    @Test
    void halfHourBoundaries_areValid() {
        // Arrange
        Set<OpeningHoursDto> blocks = Set.of(block(DayOfWeek.MONDAY, 9, 0, 12, 30));

        // Act and Assert
        assertThat(validator.isValid(blocks, context)).isTrue();
    }
}
