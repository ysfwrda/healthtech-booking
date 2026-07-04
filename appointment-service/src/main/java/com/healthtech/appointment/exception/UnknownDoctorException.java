package com.healthtech.appointment.exception;

import java.util.UUID;

public class UnknownDoctorException extends RuntimeException {
    public UnknownDoctorException(UUID doctorId) {
        super("Doctor not found in read-model: " + doctorId);
    }
}
