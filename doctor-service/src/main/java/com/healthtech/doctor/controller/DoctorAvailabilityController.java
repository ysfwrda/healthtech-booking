package com.healthtech.doctor.controller;

import com.healthtech.doctor.dto.AvailabilityResponse;
import com.healthtech.doctor.dto.OpeningHoursUpdateRequest;
import com.healthtech.doctor.dto.VacationDto;
import com.healthtech.doctor.dto.VacationRequest;
import com.healthtech.doctor.service.AvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@Tag(name = "Doctor availability")
@RequestMapping("/api/doctors/me/availability")
@RequiredArgsConstructor
public class DoctorAvailabilityController {

    private final AvailabilityService availabilityService;

    @Operation(summary = "Get the authenticated doctor's opening hours and upcoming vacations")
    @ApiResponse(responseCode = "200", description = "Opening hours and vacations that have not ended yet")
    @ApiResponse(responseCode = "403", description = "The token is not a doctor token")
    @ApiResponse(responseCode = "404", description = "The doctor no longer exists")
    @GetMapping
    public ResponseEntity<AvailabilityResponse> getAvailability(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(availabilityService.getAvailability(UUID.fromString(jwt.getSubject())));
    }

    @Operation(summary = "Replace the authenticated doctor's weekly opening hours",
            description = "The change is always saved. Appointments that no longer fit are flagged by the appointment "
                    + "service and their patients are notified.")
    @ApiResponse(responseCode = "200", description = "Opening hours replaced; the new availability is returned")
    @ApiResponse(responseCode = "400", description = "Validation failed: blocks off the half-hour grid, end not after start, or overlapping blocks")
    @ApiResponse(responseCode = "403", description = "The token is not a doctor token")
    @ApiResponse(responseCode = "404", description = "The doctor no longer exists")
    @PutMapping("/opening-hours")
    public ResponseEntity<AvailabilityResponse> replaceOpeningHours(@Valid @RequestBody OpeningHoursUpdateRequest request,
                                                                    @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(availabilityService.replaceOpeningHours(
                UUID.fromString(jwt.getSubject()), request.getOpeningHours()));
    }

    @Operation(summary = "Add a vacation (whole days, both ends inclusive)",
            description = "The vacation is always saved. Appointments on those days are flagged by the appointment "
                    + "service and their patients are notified.")
    @ApiResponse(responseCode = "201", description = "Vacation added")
    @ApiResponse(responseCode = "400", description = "Missing dates, startDate after endDate, or endDate in the past")
    @ApiResponse(responseCode = "403", description = "The token is not a doctor token")
    @ApiResponse(responseCode = "404", description = "The doctor no longer exists")
    @PostMapping("/vacations")
    public ResponseEntity<VacationDto> addVacation(@Valid @RequestBody VacationRequest request,
                                                   @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(availabilityService.addVacation(UUID.fromString(jwt.getSubject()), request));
    }

    @Operation(summary = "Remove one of the authenticated doctor's vacations")
    @ApiResponse(responseCode = "204", description = "Vacation removed")
    @ApiResponse(responseCode = "403", description = "The token is not a doctor token")
    @ApiResponse(responseCode = "404", description = "Vacation not found, or it belongs to another doctor")
    @DeleteMapping("/vacations/{vacationId}")
    public ResponseEntity<Void> removeVacation(@PathVariable UUID vacationId,
                                               @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        availabilityService.removeVacation(UUID.fromString(jwt.getSubject()), vacationId);
        return ResponseEntity.noContent().build();
    }
}
