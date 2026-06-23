package com.healthtech.patient.dto;

import com.healthtech.patient.domain.InsuranceType;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterRequest {
    private String firstName;
    private String lastName;
    private String userName;
    private String password;
    private LocalDate dateOfBirth;
    private String email;
    private InsuranceType insuranceType;
}
