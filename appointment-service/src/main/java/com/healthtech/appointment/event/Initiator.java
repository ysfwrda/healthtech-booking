package com.healthtech.appointment.event;

// Who caused a cancel or change. Events from before this field existed have none; consumers read that as PATIENT.
public enum Initiator {
    PATIENT,
    DOCTOR
}
