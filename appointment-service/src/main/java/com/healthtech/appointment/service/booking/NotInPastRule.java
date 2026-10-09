package com.healthtech.appointment.service.booking;

import com.healthtech.appointment.exception.SlotInPastException;
import com.healthtech.appointment.readmodel.ValidDoctor;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

// Runs after the alignment and opening-hours rules, so those errors keep priority. A slot
// starting exactly now is accepted, matching AvailabilityService, which keeps it as available.
@Component
@Order(3)
@RequiredArgsConstructor
public class NotInPastRule implements BookingRule {

    private final Clock clock;

    @Override
    public void check(LocalDateTime slotStart, ValidDoctor doctor) {
        if (slotStart.isBefore(LocalDateTime.now(clock))) {
            throw new SlotInPastException();
        }
    }
}
