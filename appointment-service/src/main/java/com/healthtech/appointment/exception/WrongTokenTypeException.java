package com.healthtech.appointment.exception;

public class WrongTokenTypeException extends RuntimeException {
    public WrongTokenTypeException() {
        super("Token role not permitted for this action");
    }
}
