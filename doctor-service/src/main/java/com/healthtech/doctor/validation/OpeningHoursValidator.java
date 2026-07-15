package com.healthtech.doctor.validation;

import com.healthtech.doctor.dto.OpeningHoursDto;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class OpeningHoursValidator implements ConstraintValidator<ValidOpeningHours, Set<OpeningHoursDto>> {

    @Override
    public boolean isValid(Set<OpeningHoursDto> openingHours, ConstraintValidatorContext context) {
        if (openingHours == null || openingHours.isEmpty()) {
            return true;
        }

        for (OpeningHoursDto block : openingHours) {
            if (block.getDayOfWeek() == null || block.getStartTime() == null || block.getEndTime() == null) {
                continue;
            }
            if (!block.getStartTime().isBefore(block.getEndTime())) {
                return fail(context, "Opening hours block for " + block.getDayOfWeek()
                        + " must have a startTime before endTime");
            }
        }

        Map<DayOfWeek, List<OpeningHoursDto>> blocksByDay = openingHours.stream()
                .filter(block -> block.getDayOfWeek() != null && block.getStartTime() != null && block.getEndTime() != null)
                .collect(Collectors.groupingBy(OpeningHoursDto::getDayOfWeek));

        for (Map.Entry<DayOfWeek, List<OpeningHoursDto>> entry : blocksByDay.entrySet()) {
            List<OpeningHoursDto> blocks = entry.getValue();
            for (int i = 0; i < blocks.size(); i++) {
                for (int j = i + 1; j < blocks.size(); j++) {
                    if (overlaps(blocks.get(i), blocks.get(j))) {
                        return fail(context, "Overlapping opening hours on " + entry.getKey());
                    }
                }
            }
        }

        return true;
    }

    private boolean overlaps(OpeningHoursDto a, OpeningHoursDto b) {
        return a.getStartTime().isBefore(b.getEndTime()) && b.getStartTime().isBefore(a.getEndTime());
    }

    private boolean fail(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
