package com.healthtech.doctor.dto;

import com.healthtech.doctor.domain.Language;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateDoctorRequest {
    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String phoneNumber;

    @NotNull
    @Valid
    private AddressDto address;

    @NotEmpty
    private Set<UUID> specialtyIds;

    @NotEmpty
    private Set<@Valid OpeningHoursDto> openingHours;

    @NotEmpty
    private  Set<Language> languages;
}
