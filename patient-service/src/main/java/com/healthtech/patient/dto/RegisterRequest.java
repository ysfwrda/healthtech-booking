package com.healthtech.patient.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.healthtech.patient.domain.InsuranceType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterRequest {
    @NotBlank
    @Schema(example = "Max")
    private String firstName;

    @NotBlank
    @Schema(example = "Mustermann")
    private String lastName;

    @NotBlank
    @Schema(example = "max.mustermann")
    private String username;

    @NotBlank
    @Size(min = 8, max = 72)
    @Schema(example = "ChangeMe123!", description = "8 to 72 characters (BCrypt ignores anything beyond 72 bytes)")
    private String password;

    @NotNull
    @Schema(example = "1990-05-17")
    private LocalDate dateOfBirth;

    @NotBlank @Email
    @Schema(example = "max.mustermann@example.com")
    private String email;

    @NotNull
    private InsuranceType insuranceType;
}
