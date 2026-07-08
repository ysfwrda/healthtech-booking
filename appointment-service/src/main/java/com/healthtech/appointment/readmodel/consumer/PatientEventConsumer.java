package com.healthtech.appointment.readmodel.consumer;

import com.healthtech.appointment.event.PatientRegistered;
import com.healthtech.appointment.readmodel.ValidPatient;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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
    public void onPatientRegistered(PatientRegistered event) {
        // The patient-registered event doesn't carry the originating HTTP request's correlation id
        // (it isn't threaded through Kafka headers), so a fresh one is generated for consumer-side
        // processing. Propagating the producer's id via Kafka headers is a reasonable future
        // enhancement, out of scope for this pass.
        MDC.put("correlationId", UUID.randomUUID().toString());
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
            MDC.remove("correlationId");
        }
    }
}
