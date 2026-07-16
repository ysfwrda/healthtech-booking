package com.healthtech.appointment.readmodel.consumer;

import com.healthtech.appointment.event.DoctorRegistered;
import com.healthtech.appointment.event.OpeningHoursPayload;
import com.healthtech.appointment.filter.CorrelationIdFilter;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DoctorEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(DoctorEventConsumer.class);

    private final ValidDoctorRepository validDoctorRepository;

    @KafkaListener(
            topics = "doctor.registered",
            containerFactory = "doctorRegisteredKafkaListenerFactory"
    )
    public void onDoctorRegistered(
            DoctorRegistered event,
            @Header(value = CorrelationIdFilter.CORRELATION_ID_HEADER, required = false) byte[] correlationIdHeader) {
        // Correlation id is read from the Kafka header the producer attached, tying this
        // consumer's log lines back to the HTTP request that triggered the event. Falls back
        // to a fresh id if the header is absent (e.g. a message produced before propagation
        // existed, or one published outside a request context such as the demo seeder).
        MDC.put(CorrelationIdFilter.MDC_KEY, resolveCorrelationId(correlationIdHeader));
        try {
            Set<OpeningHours> openingHours = event.getOpeningHours() == null ? Set.of() :
                    event.getOpeningHours().stream()
                            .map(this::toOpeningHours)
                            .collect(Collectors.toSet());

            ValidDoctor doctor = ValidDoctor.builder()
                    .doctorId(event.getDoctorId())
                    .firstName(event.getFirstName())
                    .lastName(event.getLastName())
                    .openingHours(openingHours)
                    .build();
            validDoctorRepository.save(doctor);
            log.info("Projected doctor read-model, doctorId {}", event.getDoctorId());
        } catch (RuntimeException e) {
            log.error("Failed to project doctor read-model, doctorId {}", event.getDoctorId(), e);
            throw e;
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private OpeningHours toOpeningHours(OpeningHoursPayload payload) {
        return OpeningHours.builder()
                .dayOfWeek(payload.getDayOfWeek())
                .startTime(payload.getStartTime())
                .endTime(payload.getEndTime())
                .build();
    }

    private String resolveCorrelationId(byte[] correlationIdHeader) {
        return correlationIdHeader != null
                ? new String(correlationIdHeader, StandardCharsets.UTF_8)
                : UUID.randomUUID().toString();
    }
}
