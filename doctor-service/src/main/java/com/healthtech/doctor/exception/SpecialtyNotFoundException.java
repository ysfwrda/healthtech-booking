package com.healthtech.doctor.exception;

import java.util.UUID;

public class SpecialtyNotFoundException extends RuntimeException {
    public SpecialtyNotFoundException(UUID specialtyId) {
        super("specialty not found: " + specialtyId);
    }
}
