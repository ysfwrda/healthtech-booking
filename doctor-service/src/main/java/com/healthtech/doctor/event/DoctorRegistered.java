package com.healthtech.doctor.event;

import lombok.*;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DoctorRegistered {
    private UUID eventId;
    private UUID doctorId;
    private String firstName;
    private String lastName;
    private Set<OpeningHoursData> openingHours;
    private LocalDateTime registeredAt;
    // True only for the seeded demo doctors: their bookings are confirmed on the spot.
    private boolean autoConfirmBookings;
}
