package com.healthtech.patient.controller;

import com.healthtech.patient.dto.PatientResponse;
import com.healthtech.patient.service.PatientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@Tag(name = "Patients")
@RequestMapping("/api/patients")
@RequiredArgsConstructor
public class PatientController {
    private final PatientService patientService;

    @Operation(summary = "Get a patient profile (own profile only)")
    @GetMapping("/{id}")
    public ResponseEntity<PatientResponse> getPatientProfile(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt){
        UUID requesterId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(patientService.getPatientProfileById(id, requesterId));
    }
}
