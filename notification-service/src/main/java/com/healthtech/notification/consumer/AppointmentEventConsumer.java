package com.healthtech.notification.consumer;

import com.healthtech.notification.event.AppointmentBooked;
import com.healthtech.notification.event.AppointmentCancelled;
import com.healthtech.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AppointmentEventConsumer {

    private final NotificationService notificationService;

    @KafkaListener(
            topics = "appointment.booked",
            groupId = "notification-group",
            containerFactory = "bookedKafkaListenerContainerFactory"
    )
    public void consumeBookedEvent(AppointmentBooked event) {
        notificationService.createForBookedAppointment(event);
    }

    @KafkaListener(
            topics = "appointment.cancelled",
            groupId = "notification-group",
            containerFactory = "cancelledKafkaListenerContainerFactory"
    )
    public void consumeCancelledEvent(AppointmentCancelled event) {
        notificationService.createForCancelledAppointment(event);
    }
}
