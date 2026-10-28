package com.healthtech.appointment.exception;

public class SlotOnVacationException extends RuntimeException {
    public SlotOnVacationException() {
        super("The doctor is on vacation that day");
    }
}
