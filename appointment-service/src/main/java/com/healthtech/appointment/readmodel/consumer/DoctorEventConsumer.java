package com.healthtech.appointment.readmodel.consumer;

import com.healthtech.appointment.event.DoctorRegistered;
import com.healthtech.appointment.event.OpeningHoursPayload;
import com.healthtech.appointment.correlation.ConsumerCorrelation;
import com.healthtech.appointment.correlation.CorrelationId;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DoctorEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(DoctorEventConsumer.class);

    private final ValidDoctorRepository validDoctorRepository;
    private final TransactionOperations transactionOperations;

    // An unknown doctor gets a row with the hours from the event. For a doctor that is already known, the
    // event only refreshes the names and autoConfirmBookings: the hours and vacations now follow
    // doctor.availability-updated, and a re-published doctor.registered must not undo them. The row is locked
    // like the availability listener locks it, so the two cannot overwrite each other's version.
    @KafkaListener(
            topics = "doctor.registered",
            containerFactory = "doctorRegisteredKafkaListenerFactory"
    )
    public void onDoctorRegistered(
            DoctorRegistered event,
            @Header(value = CorrelationId.HEADER, required = false) byte[] correlationIdHeader) {
        ConsumerCorrelation.runWith(correlationIdHeader, () -> {
            try {
                transactionOperations.executeWithoutResult(status ->
                        validDoctorRepository.findByIdForUpdate(event.getDoctorId()).ifPresentOrElse(
                                existing -> {
                                    existing.setFirstName(event.getFirstName());
                                    existing.setLastName(event.getLastName());
                                    existing.setAutoConfirmBookings(event.isAutoConfirmBookings());
                                    validDoctorRepository.save(existing);
                                },
                                () -> validDoctorRepository.save(newDoctor(event))));
                log.info("Projected doctor read-model, doctorId {}", event.getDoctorId());
            } catch (RuntimeException e) {
                log.error("Failed to project doctor read-model, doctorId {}", event.getDoctorId(), e);
                throw e;
            }
        });
    }

    private ValidDoctor newDoctor(DoctorRegistered event) {
        Set<OpeningHours> openingHours = event.getOpeningHours() == null ? Set.of() :
                event.getOpeningHours().stream()
                        .map(this::toOpeningHours)
                        .collect(Collectors.toSet());
        return ValidDoctor.builder()
                .doctorId(event.getDoctorId())
                .firstName(event.getFirstName())
                .lastName(event.getLastName())
                .openingHours(openingHours)
                .autoConfirmBookings(event.isAutoConfirmBookings())
                .build();
    }

    private OpeningHours toOpeningHours(OpeningHoursPayload payload) {
        return OpeningHours.builder()
                .dayOfWeek(payload.getDayOfWeek())
                .startTime(payload.getStartTime())
                .endTime(payload.getEndTime())
                .build();
    }
}
