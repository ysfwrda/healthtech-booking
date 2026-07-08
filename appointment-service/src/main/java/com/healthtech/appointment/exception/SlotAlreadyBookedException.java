package com.healthtech.appointment.exception;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
public class SlotAlreadyBookedException extends RuntimeException {
    private final UUID doctorId;
    private final LocalDateTime slot;

    public SlotAlreadyBookedException(UUID doctorId, LocalDateTime slot) {
        super("The slot is already booked");
        this.doctorId = doctorId;
        this.slot = slot;
    }
}
