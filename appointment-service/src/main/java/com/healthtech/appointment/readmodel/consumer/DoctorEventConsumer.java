package com.healthtech.appointment.readmodel.consumer;

import com.healthtech.appointment.event.DoctorRegistered;
import com.healthtech.appointment.event.OpeningHoursPayload;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DoctorEventConsumer {

    private final ValidDoctorRepository validDoctorRepository;

    @KafkaListener(
            topics = "doctor.registered",
            containerFactory = "doctorRegisteredKafkaListenerFactory"
    )
    public void onDoctorRegistered(DoctorRegistered event) {
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
    }

    private OpeningHours toOpeningHours(OpeningHoursPayload payload) {
        return OpeningHours.builder()
                .dayOfWeek(payload.getDayOfWeek())
                .startTime(payload.getStartTime())
                .endTime(payload.getEndTime())
                .build();
    }
}
