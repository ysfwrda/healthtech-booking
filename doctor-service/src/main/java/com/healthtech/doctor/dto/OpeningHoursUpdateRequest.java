package com.healthtech.doctor.dto;

import com.healthtech.doctor.validation.ValidOpeningHours;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpeningHoursUpdateRequest {
    // Replaces all weekly blocks. An empty set is allowed: the doctor then offers no slots.
    @NotNull
    @ValidOpeningHours
    private Set<@Valid OpeningHoursDto> openingHours;
}
