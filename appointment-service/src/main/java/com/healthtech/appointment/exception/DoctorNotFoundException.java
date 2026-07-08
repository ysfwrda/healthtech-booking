package com.healthtech.appointment.exception;

import java.util.UUID;

public class DoctorNotFoundException extends RuntimeException {
    public DoctorNotFoundException(UUID doctorId) {
        super("Doctor not found in read-model: " + doctorId);
    }
}
