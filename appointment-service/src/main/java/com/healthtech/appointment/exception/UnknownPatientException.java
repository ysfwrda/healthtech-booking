package com.healthtech.appointment.exception;

import java.util.UUID;

public class UnknownPatientException extends RuntimeException {
    public UnknownPatientException(UUID patientId) {
        super("Patient not found in read-model: " + patientId);
    }
}
