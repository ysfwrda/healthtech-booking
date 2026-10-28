package com.healthtech.notification.domain;

import java.time.LocalDateTime;

public enum NotificationType {
    APPOINTMENT_BOOKED("Appointment booked for ", ""),
    APPOINTMENT_CANCELLED("Appointment cancelled for ", ""),
    APPOINTMENT_CHANGED("Appointment changed to ", ""),
    APPOINTMENT_CONFIRMED("Appointment confirmed for ", ""),
    APPOINTMENT_RESCHEDULE_REQUIRED("Your doctor is no longer available on ", ", please reschedule or cancel");

    private static final String BY_DOCTOR = " by your doctor";

    private final String messagePrefix;
    private final String messageSuffix;

    NotificationType(String messagePrefix, String messageSuffix) {
        this.messagePrefix = messagePrefix;
        this.messageSuffix = messageSuffix;
    }

    // byDoctor adds " by your doctor" to a message that does not already name the doctor as its cause.
    public String messageFor(LocalDateTime appointmentDateTime, boolean byDoctor) {
        return messagePrefix + appointmentDateTime + messageSuffix + (byDoctor ? BY_DOCTOR : "");
    }
}
