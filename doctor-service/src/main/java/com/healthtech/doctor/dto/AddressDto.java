package com.healthtech.doctor.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddressDto {
    @NotBlank
    private String street;
    @NotBlank
    private String houseNumber;
    @NotBlank
    private String postalCode;
    @NotBlank
    private String city;
    private String state;
    @NotBlank
    private String country;
}
