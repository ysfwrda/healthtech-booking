package com.healthtech.appointment.readmodel.consumer;

import com.healthtech.appointment.event.PatientRegistered;
import com.healthtech.appointment.readmodel.ValidPatient;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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
        }
    }
}
