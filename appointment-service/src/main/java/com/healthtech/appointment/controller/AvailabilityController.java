package com.healthtech.appointment.controller;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import com.healthtech.appointment.dto.AvailableSlotsResponse;
import com.healthtech.appointment.service.AvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@Tag(name = "Availability")
@RequestMapping("/api/availability")
@RequiredArgsConstructor
public class AvailabilityController {
    private final AvailabilityService availabilityService;

    @Operation(summary = "Get a doctor's available slots for a date",
            description = "Slots are on the 30-minute grid within the doctor's opening hours, minus booked ones. Dates before today return an empty list; on today, slots that started before now are left out.")
    @ApiResponse(responseCode = "200", description = "Free slots for that day, possibly empty. A date before today always returns an empty list")
    @ApiResponse(responseCode = "400", description = "Missing or malformed doctorId or date")
    @ApiResponse(responseCode = "404", description = "Doctor not found")
    @SecurityRequirements
    @GetMapping
    public AvailableSlotsResponse getAvailability(
            @Parameter(description = "Id of a doctor, taken from GET /api/doctors", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @RequestParam UUID doctorId,
            @Parameter(description = "Day to check, ISO format yyyy-MM-dd", example = "2026-10-12")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return availabilityService.getAvailableSlots(doctorId, date);
    }
}
