package com.healthtech.doctor.controller;

import com.healthtech.doctor.domain.Language;
import com.healthtech.doctor.dto.DoctorResponse;
import com.healthtech.doctor.dto.DoctorSummaryResponse;
import com.healthtech.doctor.service.DoctorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Doctors")
@RequestMapping("/api/doctors")
@RequiredArgsConstructor
public class DoctorController {

    private final DoctorService doctorService;

    @Operation(summary = "Get a doctor by id")
    @SecurityRequirements
    @GetMapping("/{id}")
    public ResponseEntity<DoctorResponse> getDoctorById(@PathVariable UUID id) {
        return ResponseEntity.ok(doctorService.getDoctorById(id));
    }

    @Operation(summary = "Search doctors by specialty and/or language")
    @SecurityRequirements
    @GetMapping
    public ResponseEntity<List<DoctorSummaryResponse>> findDoctors(
            @RequestParam(required = false) String specialty,
            @RequestParam(required = false) Language language) {
        return ResponseEntity.ok(doctorService.findDoctors(specialty, language));
    }
}
