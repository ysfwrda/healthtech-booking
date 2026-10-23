package com.healthtech.appointment.service.booking;

import com.healthtech.appointment.exception.SlotOnVacationException;
import com.healthtech.appointment.readmodel.ValidDoctor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

// Runs after the alignment, opening-hours and past rules, so those errors keep priority.
@Component
@Order(4)
public class NotOnVacationRule implements BookingRule {

    @Override
    public void check(LocalDateTime slotStart, ValidDoctor doctor) {
        if (doctor.isOnVacation(slotStart.toLocalDate())) {
            throw new SlotOnVacationException();
        }
    }
}
