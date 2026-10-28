package com.healthtech.appointment.readmodel.consumer;

import com.healthtech.appointment.correlation.ConsumerCorrelation;
import com.healthtech.appointment.correlation.CorrelationId;
import com.healthtech.appointment.event.DoctorAvailabilityUpdated;
import com.healthtech.appointment.service.AvailabilityChangeService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DoctorAvailabilityConsumer {
    private static final Logger log = LoggerFactory.getLogger(DoctorAvailabilityConsumer.class);

    private final AvailabilityChangeService availabilityChangeService;

    @KafkaListener(
            topics = "doctor.availability-updated",
            containerFactory = "doctorAvailabilityKafkaListenerFactory"
    )
    public void onDoctorAvailabilityUpdated(
            DoctorAvailabilityUpdated event,
            @Header(value = CorrelationId.HEADER, required = false) byte[] correlationIdHeader) {
        ConsumerCorrelation.runWith(correlationIdHeader, () -> {
            try {
                availabilityChangeService.handle(event);
            } catch (RuntimeException e) {
                log.error("Failed to apply availability change, doctorId {}, version {}",
                        event.getDoctorId(), event.getVersion(), e);
                throw e;
            }
        });
    }
}
