package com.healthtech.appointment.exception;

public class SlotNotAlignedException extends RuntimeException {
    public SlotNotAlignedException() {
        super("The slot is not aligned");
    }

}
