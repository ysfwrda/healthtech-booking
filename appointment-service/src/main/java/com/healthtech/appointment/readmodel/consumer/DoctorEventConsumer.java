package com.healthtech.appointment.readmodel.consumer;

import com.healthtech.appointment.event.DoctorRegistered;
import com.healthtech.appointment.event.OpeningHoursPayload;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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
    public void onDoctorRegistered(DoctorRegistered event) {
        // The doctor-registered event doesn't carry the originating HTTP request's correlation id
        // (it isn't threaded through Kafka headers), so a fresh one is generated for consumer-side
        // processing. Propagating the producer's id via Kafka headers is a reasonable future
        // enhancement, out of scope for this pass.
        MDC.put("correlationId", UUID.randomUUID().toString());
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
            MDC.remove("correlationId");
        }
    }

    private OpeningHours toOpeningHours(OpeningHoursPayload payload) {
        return OpeningHours.builder()
                .dayOfWeek(payload.getDayOfWeek())
                .startTime(payload.getStartTime())
                .endTime(payload.getEndTime())
                .build();
    }
}
