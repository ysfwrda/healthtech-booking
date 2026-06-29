package com.healthtech.doctor.dto;

import com.healthtech.doctor.domain.Language;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoctorResponse {
    private UUID id;
    private String firstName;
    private String lastName;
    private String email;
    private String phoneNumber;
    private AddressDto address;
    private Set<SpecialtyDto> specialties;
    private Set<OpeningHoursDto> openingHours;
    private Set<Language> languages;
    private LocalDateTime registeredAt;
}
