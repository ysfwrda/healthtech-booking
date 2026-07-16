package com.healthtech.notification.consumer;

import com.healthtech.notification.event.AppointmentBooked;
import com.healthtech.notification.event.AppointmentCancelled;
import com.healthtech.notification.filter.CorrelationIdFilter;
import com.healthtech.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
            @Header(value = CorrelationIdFilter.CORRELATION_ID_HEADER, required = false) byte[] correlationIdHeader) {
        // Correlation id is read from the Kafka header the producer attached, tying this
        // consumer's log lines back to the HTTP request that triggered the event. Falls back
        // to a fresh id if the header is absent (e.g. a message produced before propagation
        // existed, or one published outside a request context such as the demo seeder).
        MDC.put(CorrelationIdFilter.MDC_KEY, resolveCorrelationId(correlationIdHeader));
        try {
            notificationService.createForBookedAppointment(event);
            log.info("Notification recorded for booked appointment, appointmentId {}", event.getAppointmentId());
        } catch (RuntimeException e) {
            log.error("Failed to record notification for booked appointment, appointmentId {}",
                    event.getAppointmentId(), e);
            throw e;
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    @KafkaListener(
            topics = "appointment.cancelled",
            groupId = "notification-group",
            containerFactory = "cancelledKafkaListenerContainerFactory"
    )
    public void consumeCancelledEvent(
            AppointmentCancelled event,
            @Header(value = CorrelationIdFilter.CORRELATION_ID_HEADER, required = false) byte[] correlationIdHeader) {
        MDC.put(CorrelationIdFilter.MDC_KEY, resolveCorrelationId(correlationIdHeader));
        try {
            notificationService.createForCancelledAppointment(event);
            log.info("Notification recorded for cancelled appointment, appointmentId {}", event.getAppointmentId());
        } catch (RuntimeException e) {
            log.error("Failed to record notification for cancelled appointment, appointmentId {}",
                    event.getAppointmentId(), e);
            throw e;
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private String resolveCorrelationId(byte[] correlationIdHeader) {
        return correlationIdHeader != null
                ? new String(correlationIdHeader, StandardCharsets.UTF_8)
                : UUID.randomUUID().toString();
    }
}
