package com.healthtech.appointment.service.booking;

import com.healthtech.appointment.readmodel.ValidDoctor;

import java.time.LocalDateTime;

// One check a requested slot must pass before it is booked. AppointmentService runs every
// BookingRule bean in @Order, so a new rule (e.g. a minimum notice period) is a new class,
// not an edit to the booking flow. A rule rejects a slot by throwing the exception that
// GlobalExceptionHandler maps to the matching problem detail.
public interface BookingRule {

    void check(LocalDateTime slotStart, ValidDoctor doctor);
}
