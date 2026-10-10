package com.healthtech.notification.consumer;

import com.healthtech.notification.event.AppointmentBooked;
import com.healthtech.notification.event.AppointmentCancelled;
import com.healthtech.notification.event.AppointmentChanged;
import com.healthtech.notification.event.AppointmentNotificationEvent;
import com.healthtech.notification.correlation.ConsumerCorrelation;
import com.healthtech.notification.correlation.CorrelationId;
import com.healthtech.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AppointmentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AppointmentEventConsumer.class);

    private final NotificationService notificationService;

    @KafkaListener(
            topics = "appointment.booked",
            groupId = "notification-group",
            containerFactory = "bookedKafkaListenerContainerFactory"
    )
    public void consumeBookedEvent(
            AppointmentBooked event,
            @Header(value = CorrelationId.HEADER, required = false) byte[] correlationIdHeader) {
        handle(event, correlationIdHeader);
    }

    @KafkaListener(
            topics = "appointment.cancelled",
            groupId = "notification-group",
            containerFactory = "cancelledKafkaListenerContainerFactory"
    )
    public void consumeCancelledEvent(
            AppointmentCancelled event,
            @Header(value = CorrelationId.HEADER, required = false) byte[] correlationIdHeader) {
        handle(event, correlationIdHeader);
    }

    @KafkaListener(
            topics = "appointment.changed",
            groupId = "notification-group",
            containerFactory = "changedKafkaListenerContainerFactory"
    )
    public void consumeChangedEvent(
            AppointmentChanged event,
            @Header(value = CorrelationId.HEADER, required = false) byte[] correlationIdHeader) {
        handle(event, correlationIdHeader);
    }

    // One listener per topic (Kafka binds topics per method); the correlation, recording and
    // logging around each message are shared here.
    private void handle(AppointmentNotificationEvent event, byte[] correlationIdHeader) {
        ConsumerCorrelation.runWith(correlationIdHeader, () -> {
            try {
                notificationService.record(event);
                log.info("Notification recorded, type {}, appointmentId {}",
                        event.notificationType(), event.getAppointmentId());
            } catch (RuntimeException e) {
                log.error("Failed to record notification, type {}, appointmentId {}",
                        event.notificationType(), event.getAppointmentId(), e);
                throw e;
            }
        });
    }
}
