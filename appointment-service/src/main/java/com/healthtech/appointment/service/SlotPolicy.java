package com.healthtech.appointment.service;

import com.healthtech.appointment.readmodel.OpeningHours;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

// The single home of the booking grid: how long a slot is, which start times are valid,
// and which slots an opening-hours block offers. Booking and availability both read it, so
// they share one slot length. slotsWithin does not apply isAligned: a block starting off the
// grid (e.g. 09:15) yields slots that booking will reject.
@Component
public class SlotPolicy {

    public static final int SLOT_DURATION_MINUTES = 30;

    public boolean isAligned(LocalTime start) {
        return start.getMinute() % SLOT_DURATION_MINUTES == 0
                && start.getSecond() == 0
                && start.getNano() == 0;
    }

    public LocalTime slotEnd(LocalTime start) {
        return start.plusMinutes(SLOT_DURATION_MINUTES);
    }

    // Every slot start in the block whose full slot still ends by closing time.
    public List<LocalDateTime> slotsWithin(LocalDate date, OpeningHours openingHours) {
        List<LocalDateTime> slots = new ArrayList<>();
        LocalDateTime currentSlot = date.atTime(openingHours.getStartTime());
        LocalDateTime lastSlot = date.atTime(openingHours.getEndTime().minusMinutes(SLOT_DURATION_MINUTES));
        while (!currentSlot.isAfter(lastSlot)) {
            slots.add(currentSlot);
            currentSlot = currentSlot.plusMinutes(SLOT_DURATION_MINUTES);
        }
        return slots;
    }
}
