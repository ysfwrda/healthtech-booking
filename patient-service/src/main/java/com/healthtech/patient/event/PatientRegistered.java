package com.healthtech.patient.event;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PatientRegistered {
    // TODO: Pending ADR-005
    private UUID id;
}
