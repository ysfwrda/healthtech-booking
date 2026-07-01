package com.healthtech.appointment.event;

import lombok.*;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoctorRegistered {
    private UUID eventId;
    private UUID doctorId;
    private String firstName;
    private String lastName;
    private LocalDateTime registeredAt;
    private Set<OpeningHoursPayload> openingHours;
}
