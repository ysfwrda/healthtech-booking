package com.healthtech.patient.controller;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.healthtech.patient.dto.AuthResponse;
import com.healthtech.patient.dto.LoginRequest;
import com.healthtech.patient.dto.RegisterRequest;
import com.healthtech.patient.service.AuthService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Patient authentication")
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController{
    private final AuthService authService;
    @Operation(summary = "Log in as a patient and obtain a JWT")
    @ApiResponse(responseCode = "200", description = "Logged in; the response contains the JWT")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "401", description = "Invalid username or password")
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));

    }

    @Operation(summary = "Register a new patient and obtain a JWT")
    @ApiResponse(responseCode = "201", description = "Registered; the response contains the JWT",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "409", description = "Username or email already registered")
    @SecurityRequirements
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.register(request));
    }
}
