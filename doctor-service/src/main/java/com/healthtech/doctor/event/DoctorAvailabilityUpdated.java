package com.healthtech.doctor.event;

import lombok.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

// Full snapshot of a doctor's availability (not a delta), so applying it twice is harmless and
// a lost event is healed by the next one. version is Doctor.availabilityVersion at the time of the write.
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DoctorAvailabilityUpdated {
    private UUID eventId;
    private UUID doctorId;
    private Set<OpeningHoursData> openingHours;
    private List<VacationData> vacations;
    private long version;
}
