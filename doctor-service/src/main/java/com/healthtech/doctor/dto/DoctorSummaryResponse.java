package com.healthtech.doctor.dto;

import com.healthtech.doctor.domain.Language;
import lombok.*;

import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoctorSummaryResponse {
    private UUID id;
    private String firstName;
    private String lastName;
    private Set<SpecialtyDto> specialties;
    private Set<Language> languages;
    private String postalCode;
    private String city;
}
