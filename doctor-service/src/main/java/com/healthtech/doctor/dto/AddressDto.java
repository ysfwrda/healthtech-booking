package com.healthtech.doctor.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddressDto {
    @NotBlank
    @Schema(example = "Hauptstrasse")
    private String street;
    @NotBlank
    @Schema(example = "12")
    private String houseNumber;
    @NotBlank
    @Schema(example = "10115")
    private String postalCode;
    @NotBlank
    @Schema(example = "Berlin")
    private String city;
    @Schema(example = "Berlin")
    private String state;
    @NotBlank
    @Schema(example = "Germany")
    private String country;
}
