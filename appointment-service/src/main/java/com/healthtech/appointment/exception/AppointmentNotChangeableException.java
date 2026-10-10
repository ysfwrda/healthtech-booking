package com.healthtech.appointment.exception;

import java.util.UUID;

public class AppointmentNotChangeableException extends RuntimeException {
    public AppointmentNotChangeableException(UUID appointmentId) {
        super("A cancelled appointment cannot be changed: " + appointmentId);
    }
}
