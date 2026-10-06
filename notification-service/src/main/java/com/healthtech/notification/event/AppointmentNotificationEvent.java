package com.healthtech.notification.event;

import com.healthtech.notification.domain.NotificationType;

import java.util.UUID;

// An appointment event this service turns into a notification. Each event type says which
// notification it produces, so NotificationService records any of them without knowing the
// concrete type and never needs a per-type method. A new event type still needs its own
// event class, listener method, Kafka beans and NotificationType value. The methods without
// a "get" prefix are not JSON properties, so they play no part in deserializing the payload.
public interface AppointmentNotificationEvent {

    UUID getAppointmentId();

    UUID getPatientId();

    UUID getDoctorId();

    NotificationType notificationType();

    String notificationMessage();
}
