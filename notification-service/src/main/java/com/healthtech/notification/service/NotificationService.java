package com.healthtech.notification.service;

import com.healthtech.notification.domain.Notification;
import com.healthtech.notification.event.AppointmentNotificationEvent;
import com.healthtech.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    // Unique index on notification.event_id (schema-postgresql.sql); the database decides whether an event was already recorded.
    static final String EVENT_ID_INDEX = "ux_notification_event_id";

    private final NotificationRepository notificationRepository;

    // Not @Transactional: saveAndFlush runs in its own transaction, so a duplicate-key rollback is
    // finished before it is caught here.
    public void record(AppointmentNotificationEvent event) {
        Notification notification = Notification.builder()
                .eventId(event.getEventId())
                .appointmentId(event.getAppointmentId())
                .patientId(event.getPatientId())
                .doctorId(event.getDoctorId())
                .type(event.notificationType())
                .message(event.notificationType().messageFor(event.getDateTime()))
                .build();

        try {
            notificationRepository.saveAndFlush(notification);
        } catch (DataIntegrityViolationException e) {
            if (!violatesEventIdIndex(e)) {
                throw e;
            }
            log.info("Event already recorded, ignoring duplicate, eventId {}, appointmentId {}",
                    event.getEventId(), event.getAppointmentId());
        }
    }

    private static boolean violatesEventIdIndex(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && EVENT_ID_INDEX.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
