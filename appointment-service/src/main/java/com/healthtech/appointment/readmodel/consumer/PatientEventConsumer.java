package com.healthtech.appointment.readmodel.consumer;

import com.healthtech.appointment.event.PatientRegistered;
import com.healthtech.appointment.readmodel.ValidPatient;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PatientEventConsumer {

    private final ValidPatientRepository validPatientRepository;

    @KafkaListener(
            topics = "patient.registered",
            containerFactory = "patientRegisteredKafkaListenerFactory"
    )
    public void onPatientRegistered(PatientRegistered event) {
        ValidPatient patient = ValidPatient.builder()
                .patientId(event.getPatientId())
                .email(event.getEmail())
                .firstName(event.getFirstName())
                .lastName(event.getLastName())
                .build();
        validPatientRepository.save(patient);
    }
}
