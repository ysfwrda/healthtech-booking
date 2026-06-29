package com.healthtech.doctor.event;

import lombok.*;

import java.time.LocalDateTime;
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
    private LocalDateTime registeredAt;
}
