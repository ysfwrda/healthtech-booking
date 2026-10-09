package com.healthtech.appointment.exception;

public class ChangeWindowClosedException extends RuntimeException {
    public ChangeWindowClosedException(int minNoticeHours) {
        super("An appointment can only be changed up to " + minNoticeHours + " hours before it starts");
    }
}
