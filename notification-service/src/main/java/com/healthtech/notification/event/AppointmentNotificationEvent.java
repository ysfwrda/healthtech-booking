package com.healthtech.notification.event;

import com.healthtech.notification.domain.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

// An appointment event that says which notification it produces, so NotificationService can record
// any of them. notificationType() has no "get" prefix, so it is not a JSON property.
public interface AppointmentNotificationEvent {

    UUID getAppointmentId();

    UUID getPatientId();

    UUID getDoctorId();

    LocalDateTime getDateTime();

    NotificationType notificationType();
}
