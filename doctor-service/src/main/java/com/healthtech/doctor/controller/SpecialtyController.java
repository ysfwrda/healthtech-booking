package com.healthtech.doctor.controller;

import com.healthtech.doctor.dto.SpecialtyDto;
import com.healthtech.doctor.service.SpecialtyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "Specialties")
@RequestMapping("/api/specialties")
@RequiredArgsConstructor
public class SpecialtyController {

    private final SpecialtyService specialtyService;

    @Operation(summary = "List all medical specialties")
    @ApiResponse(responseCode = "200", description = "All specialties")
    @SecurityRequirements
    @GetMapping
    public ResponseEntity<List<SpecialtyDto>> getAllSpecialties() {
        return ResponseEntity.ok(specialtyService.getAllSpecialties());
    }
}
