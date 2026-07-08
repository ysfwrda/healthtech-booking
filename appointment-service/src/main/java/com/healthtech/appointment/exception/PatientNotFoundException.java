package com.healthtech.appointment.exception;

import java.util.UUID;

public class PatientNotFoundException extends RuntimeException {
    public PatientNotFoundException(UUID patientId) {
        super("Patient not found in read-model: " + patientId);
    }
}
