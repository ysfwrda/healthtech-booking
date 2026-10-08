package com.healthtech.doctor.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DoctorLoginRequest {
    @NotBlank
    @Email
    @Schema(example = "anna.schmidt@example.com")
    private String email;

    @NotBlank
    @Schema(example = "ChangeMe123!")
    private String password;
}
