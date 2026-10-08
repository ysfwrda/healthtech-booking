package com.healthtech.appointment.exception;

public class SlotInPastException extends RuntimeException {
    public SlotInPastException() {
        super("The slot is in the past");
    }
}
