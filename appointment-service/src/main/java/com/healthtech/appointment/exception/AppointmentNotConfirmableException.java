package com.healthtech.appointment.exception;

import java.util.UUID;

public class AppointmentNotConfirmableException extends RuntimeException {
    public AppointmentNotConfirmableException(UUID appointmentId, String reason) {
        super("The appointment cannot be confirmed (" + reason + "): " + appointmentId);
    }
}
