package com.healthtech.appointment.event;

import lombok.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

// Mirror of doctor-service's snapshot of a doctor's availability; version increases by one per doctor and write.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoctorAvailabilityUpdated {
    private UUID eventId;
    private UUID doctorId;
    private Set<OpeningHoursPayload> openingHours;
    private List<VacationPayload> vacations;
    private long version;
}
