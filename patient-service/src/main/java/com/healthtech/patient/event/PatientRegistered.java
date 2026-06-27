package com.healthtech.patient.event;

import com.healthtech.patient.domain.InsuranceType;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PatientRegistered {
    // TODO: Pending ADR-006
    private UUID id;
}
