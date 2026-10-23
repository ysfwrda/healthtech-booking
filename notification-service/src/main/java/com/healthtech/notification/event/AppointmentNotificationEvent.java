package com.healthtech.notification.event;

import com.healthtech.notification.domain.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

// An appointment event that says which notification it produces, so NotificationService can record
// any of them. notificationType() has no "get" prefix, so it is not a JSON property.
public interface AppointmentNotificationEvent {

    UUID getEventId();

    UUID getAppointmentId();

    UUID getPatientId();

    UUID getDoctorId();

    LocalDateTime getDateTime();

    NotificationType notificationType();

    // True when the doctor caused the event (a cancel or change); the message then says so.
    // No "get"/"is" prefix, so it is not a JSON property.
    default boolean initiatedByDoctor() {
        return false;
    }
}
