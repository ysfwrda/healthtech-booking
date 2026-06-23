package com.healthtech.patient.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
    private String token;
    private Long expiresIn;
    private String username;
}
