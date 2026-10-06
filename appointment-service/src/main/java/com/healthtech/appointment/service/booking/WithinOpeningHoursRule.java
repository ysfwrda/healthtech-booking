package com.healthtech.appointment.service.booking;

import com.healthtech.appointment.exception.OutsideOpeningHoursException;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.service.SlotPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Component
@Order(2)
@RequiredArgsConstructor
public class WithinOpeningHoursRule implements BookingRule {

    private final SlotPolicy slotPolicy;

    @Override
    public void check(LocalDateTime slotStart, ValidDoctor doctor) {
        LocalTime start = slotStart.toLocalTime();
        if (!doctor.isOpenFor(slotStart.getDayOfWeek(), start, slotPolicy.slotEnd(start))) {
            throw new OutsideOpeningHoursException();
        }
    }
}
