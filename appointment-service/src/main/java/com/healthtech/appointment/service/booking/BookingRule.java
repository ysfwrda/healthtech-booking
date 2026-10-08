package com.healthtech.appointment.service.booking;

import com.healthtech.appointment.readmodel.ValidDoctor;

import java.time.LocalDateTime;

// One check a requested slot must pass before it is booked. AppointmentService runs every
// BookingRule bean in @Order. A rule rejects a slot by throwing an exception that
// GlobalExceptionHandler maps to a problem detail.
public interface BookingRule {

    void check(LocalDateTime slotStart, ValidDoctor doctor);
}
