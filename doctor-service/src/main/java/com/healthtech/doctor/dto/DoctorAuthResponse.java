package com.healthtech.doctor.dto;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoctorAuthResponse {
    private UUID id;
    private String token;
    private Long expiresIn;
    private String email;
}
