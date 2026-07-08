package com.healthtech.patient.exception;

import java.util.UUID;

public class PatientAccessDeniedException extends RuntimeException {
    public PatientAccessDeniedException(UUID patientId) {
        super("user not allowed to access patient profile: " + patientId);
    }
}
