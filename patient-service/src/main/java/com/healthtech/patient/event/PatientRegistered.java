package com.healthtech.patient.event;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PatientRegistered {
    private UUID eventId;
    private UUID patientId;
    private String firstName;
    private String lastName;
    private String email;
    private LocalDateTime registeredAt;

}
