package com.healthtech.appointment.event;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientRegistered {
    private UUID eventId;
    private UUID patientId;
    private String email;
    private String firstName;
    private String lastName;
    private LocalDateTime registeredAt;
}
