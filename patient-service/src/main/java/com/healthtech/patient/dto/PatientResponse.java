package com.healthtech.patient.dto;

import com.healthtech.patient.domain.InsuranceType;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientResponse {
    private UUID id;
    private String firstName;
    private String lastName;
    private InsuranceType insuranceType;
    private LocalDate dateOfBirth;
    private String email;
    private LocalDateTime registeredAt;
}
