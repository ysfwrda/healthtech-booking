package com.healthtech.doctor.exception;

import java.util.UUID;

public class VacationNotFoundException extends RuntimeException {
    public VacationNotFoundException(UUID vacationId) {
        super("Vacation not found: " + vacationId);
    }
}
