package com.healthtech.appointment.controller;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.service.AppointmentService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Appointments")
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
public class AppointmentController {
    private final AppointmentService appointmentService;

    @Operation(summary = "Book an appointment for the authenticated patient")
    @ApiResponse(responseCode = "201", description = "Appointment booked",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppointmentResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failed, slot not aligned to the slot grid, or outside the doctor's opening hours")
    @ApiResponse(responseCode = "401", description = "Missing, invalid or expired token")
    @ApiResponse(responseCode = "403", description = "The token does not belong to a patient")
    @ApiResponse(responseCode = "404", description = "Doctor or patient not found")
    @ApiResponse(responseCode = "409", description = "Slot already booked")
    @PostMapping
    public ResponseEntity<AppointmentResponse> bookAppointment(@Valid @RequestBody AppointmentRequest request,
                                                               @AuthenticationPrincipal Jwt jwt) {
        UUID patientId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(appointmentService.bookAppointment(request, patientId));
    }

    @Operation(summary = "List the authenticated patient's appointments")
    @ApiResponse(responseCode = "200", description = "The patient's appointments, possibly empty")
    @ApiResponse(responseCode = "401", description = "Missing, invalid or expired token")
    @ApiResponse(responseCode = "403", description = "The token does not belong to a patient")
    @GetMapping
    public ResponseEntity<List<AppointmentResponse>> getMyAppointments(@AuthenticationPrincipal Jwt jwt) {
        UUID patientId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(appointmentService.getAppointmentsForPatient(patientId));
    }

    @Operation(summary = "Cancel one of the authenticated patient's appointments")
    @ApiResponse(responseCode = "200", description = "Appointment cancelled")
    @ApiResponse(responseCode = "401", description = "Missing, invalid or expired token")
    @ApiResponse(responseCode = "403", description = "The token does not belong to a patient, or the resource belongs to another patient")
    @ApiResponse(responseCode = "404", description = "Appointment not found")
    @PutMapping("/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancelAppointment(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID patientId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.OK)
                .body(appointmentService.cancelAppointment(id, patientId));
    }
}
