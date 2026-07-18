package com.healthtech.appointment.readmodel.consumer;

import com.healthtech.appointment.event.PatientRegistered;
import com.healthtech.appointment.filter.CorrelationIdFilter;
import com.healthtech.appointment.readmodel.ValidPatient;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
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
public class PatientEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(PatientEventConsumer.class);

    private final ValidPatientRepository validPatientRepository;

    @KafkaListener(
            topics = "patient.registered",
            containerFactory = "patientRegisteredKafkaListenerFactory"
    )
    public void onPatientRegistered(
            PatientRegistered event,
            @Header(value = CorrelationIdFilter.CORRELATION_ID_HEADER, required = false) byte[] correlationIdHeader) {
        // Correlation id is read from the Kafka header the producer attached, tying this
        // consumer's log lines back to the HTTP request that triggered the event. Falls back
        // to a fresh id if the header is absent (e.g. a message produced before propagation
        // existed, or one published outside a request context such as the demo seeder).
        MDC.put(CorrelationIdFilter.MDC_KEY, resolveCorrelationId(correlationIdHeader));
        try {
            ValidPatient patient = ValidPatient.builder()
                    .patientId(event.getPatientId())
                    .email(event.getEmail())
                    .firstName(event.getFirstName())
                    .lastName(event.getLastName())
                    .build();
            validPatientRepository.save(patient);
            log.info("Projected patient read-model, patientId {}", event.getPatientId());
        } catch (RuntimeException e) {
            log.error("Failed to project patient read-model, patientId {}", event.getPatientId(), e);
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
