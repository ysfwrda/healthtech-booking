package com.healthtech.doctor.dto;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpecialtyDto {
    private UUID id;
    private String name;
}
