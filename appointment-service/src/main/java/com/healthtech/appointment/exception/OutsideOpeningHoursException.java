package com.healthtech.appointment.exception;

public class OutsideOpeningHoursException extends RuntimeException {
    public OutsideOpeningHoursException() {
        super("The slot is outside opening hours");
    }
}
