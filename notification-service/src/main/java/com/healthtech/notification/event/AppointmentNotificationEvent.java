package com.healthtech.notification.event;

import com.healthtech.notification.domain.NotificationType;

import java.util.UUID;

// An appointment event this service turns into a notification. Each event type says which
// notification it produces, so NotificationService records any of them without knowing the
// concrete type: supporting a new event means a new event class and listener, not a change
// to the service. The methods without a "get" prefix are not JSON properties, so they play
// no part in deserializing the Kafka payload.
public interface AppointmentNotificationEvent {

    UUID getAppointmentId();

    UUID getPatientId();

    UUID getDoctorId();

    NotificationType notificationType();

    String notificationMessage();
}
