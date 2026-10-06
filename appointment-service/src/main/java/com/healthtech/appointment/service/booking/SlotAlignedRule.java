package com.healthtech.appointment.service.booking;

import com.healthtech.appointment.exception.SlotNotAlignedException;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.service.SlotPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

// Runs first: an off-grid time is rejected as not aligned even if it is also outside hours.
@Component
@Order(1)
@RequiredArgsConstructor
public class SlotAlignedRule implements BookingRule {

    private final SlotPolicy slotPolicy;

    @Override
    public void check(LocalDateTime slotStart, ValidDoctor doctor) {
        if (!slotPolicy.isAligned(slotStart.toLocalTime())) {
            throw new SlotNotAlignedException();
        }
    }
}
