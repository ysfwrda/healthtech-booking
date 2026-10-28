package com.healthtech.appointment.domain;

// Why a booked appointment no longer fits the doctor's availability. null on an appointment means it fits.
public enum ConflictReason {
    OUTSIDE_OPENING_HOURS,
    DOCTOR_VACATION
}
