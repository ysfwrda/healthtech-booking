package com.healthtech.notification.domain;

import java.time.LocalDateTime;

public enum NotificationType {
    APPOINTMENT_BOOKED("Appointment booked for "),
    APPOINTMENT_CANCELLED("Appointment cancelled for "),
    APPOINTMENT_CHANGED("Appointment changed to ");

    private final String messagePrefix;

    NotificationType(String messagePrefix) {
        this.messagePrefix = messagePrefix;
    }

    public String messageFor(LocalDateTime appointmentDateTime) {
        return messagePrefix + appointmentDateTime;
    }
}
