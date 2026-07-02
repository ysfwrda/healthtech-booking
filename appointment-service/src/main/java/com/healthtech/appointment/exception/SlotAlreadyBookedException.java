package com.healthtech.appointment.exception;

public class SlotAlreadyBookedException extends RuntimeException {
    public SlotAlreadyBookedException() {
        super("The slot is already booked");
    }
}
