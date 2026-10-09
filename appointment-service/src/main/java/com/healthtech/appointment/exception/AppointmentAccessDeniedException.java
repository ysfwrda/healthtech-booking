package com.healthtech.appointment.exception;

import java.util.UUID;

public class AppointmentAccessDeniedException extends RuntimeException {
    public AppointmentAccessDeniedException(UUID appointmentId) {
        super("user not allowed to access appointment: " + appointmentId);
    }
}
