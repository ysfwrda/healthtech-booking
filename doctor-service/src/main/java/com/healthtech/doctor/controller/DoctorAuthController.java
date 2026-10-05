package com.healthtech.doctor.controller;

import com.healthtech.doctor.dto.DoctorAuthResponse;
import com.healthtech.doctor.dto.DoctorLoginRequest;
import com.healthtech.doctor.dto.DoctorRegistrationRequest;
import com.healthtech.doctor.service.DoctorAuthService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Doctor authentication")
@RequestMapping("/api/doctors")
@RequiredArgsConstructor
public class DoctorAuthController {
    private final DoctorAuthService doctorAuthService;

    @Operation(summary = "Register a new doctor and obtain a JWT")
    @SecurityRequirements
    @PostMapping("/register")
    public ResponseEntity<DoctorAuthResponse> register(@Valid @RequestBody DoctorRegistrationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(doctorAuthService.register(request));
    }

    @Operation(summary = "Log in as a doctor and obtain a JWT")
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<DoctorAuthResponse> login(@Valid @RequestBody DoctorLoginRequest request) {
        return ResponseEntity.ok(doctorAuthService.login(request));
    }
}
