package com.healthtech.doctor.controller;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
    @ApiResponse(responseCode = "201", description = "Registered; the response contains the JWT",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DoctorAuthResponse.class)))
    @ApiResponse(responseCode = "404", description = "A specialty id does not exist")
    @ApiResponse(responseCode = "409", description = "Email already registered")
    @SecurityRequirements
    @PostMapping("/register")
    public ResponseEntity<DoctorAuthResponse> register(@Valid @RequestBody DoctorRegistrationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(doctorAuthService.register(request));
    }

    @Operation(summary = "Log in as a doctor and obtain a JWT")
    @ApiResponse(responseCode = "200", description = "Logged in; the response contains the JWT")
    @ApiResponse(responseCode = "401", description = "Invalid email or password")
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<DoctorAuthResponse> login(@Valid @RequestBody DoctorLoginRequest request) {
        return ResponseEntity.ok(doctorAuthService.login(request));
    }
}
