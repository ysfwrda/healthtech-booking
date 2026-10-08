package com.healthtech.doctor.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.healthtech.doctor.domain.Language;
import com.healthtech.doctor.validation.ValidOpeningHours;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoctorRegistrationRequest {
    @NotBlank
    @Schema(example = "Anna")
    private String firstName;

    @NotBlank
    @Schema(example = "Schmidt")
    private String lastName;

    @NotBlank
    @Email
    @Schema(example = "anna.schmidt@example.com")
    private String email;

    @NotBlank
    @Size(min = 8)
    @Schema(example = "ChangeMe123!", description = "At least 8 characters")
    private String password;

    @NotBlank
    @Schema(example = "+49 30 1234567")
    private String phoneNumber;

    @NotNull
    @Valid
    private AddressDto address;

    @NotEmpty
    @Schema(description = "Ids of existing specialties, taken from GET /api/specialties", example = "[\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"]")
    private Set<UUID> specialtyIds;

    @NotEmpty
    @ValidOpeningHours
    private Set<@Valid OpeningHoursDto> openingHours;

    @NotEmpty
    private Set<Language> languages;
}
