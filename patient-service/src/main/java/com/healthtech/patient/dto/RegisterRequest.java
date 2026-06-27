package com.healthtech.patient.dto;

import com.healthtech.patient.domain.InsuranceType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterRequest {
    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    @NotNull
    private LocalDate dateOfBirth;

    @NotBlank @Email
    private String email;

    @NotNull
    private InsuranceType insuranceType;
}
