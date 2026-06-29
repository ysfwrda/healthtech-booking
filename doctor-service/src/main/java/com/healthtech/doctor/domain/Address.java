package com.healthtech.doctor.domain;

import jakarta.persistence.Embeddable;
import lombok.*;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Address {
    private String street;
    private String houseNumber;
    private String postalCode;
    private String city;
    private String state;
    private String country;
}
