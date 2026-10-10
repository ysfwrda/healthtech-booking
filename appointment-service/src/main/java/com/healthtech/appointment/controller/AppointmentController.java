package com.healthtech.appointment.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.dto.AppointmentUpdateRequest;
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
    @ApiResponse(responseCode = "400", description = "Validation failed, malformed request, slot not aligned to the slot grid, outside the doctor's opening hours, or slot in the past")
    @ApiResponse(responseCode = "404", description = "Doctor or patient not found. A patient who just registered may briefly get this until their record reaches the appointment service")
    @ApiResponse(responseCode = "409", description = "Slot already booked")
    @PostMapping
    public ResponseEntity<AppointmentResponse> bookAppointment(@Valid @RequestBody AppointmentRequest request,
                                                               @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        UUID patientId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(appointmentService.bookAppointment(request, patientId));
    }

    @Operation(summary = "List the authenticated patient's appointments")
    @ApiResponse(responseCode = "200", description = "The patient's appointments, possibly empty")
    @GetMapping
    public ResponseEntity<List<AppointmentResponse>> getMyAppointments(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        UUID patientId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(appointmentService.getAppointmentsForPatient(patientId));
    }

    @Operation(summary = "Change the purpose and/or time of one of the authenticated patient's appointments",
            description = "Partial update: send only the fields to change (at least one). The new time must be a free slot "
                    + "inside the same doctor's opening hours. Allowed up to appointment.change.min-notice-hours (default 48) "
                    + "before the current start time; the new time itself only has to be in the future.")
    @ApiResponse(responseCode = "200", description = "Appointment changed (or already as requested)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppointmentResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failed (no field given, notes too long), slot not aligned to the slot grid, outside the doctor's opening hours, or slot in the past")
    @ApiResponse(responseCode = "403", description = "The token is not a patient token, or the resource belongs to another patient")
    @ApiResponse(responseCode = "404", description = "Appointment not found, or the doctor is not known to the appointment service")
    @ApiResponse(responseCode = "409", description = "Slot already booked, the appointment is cancelled, or it starts in less than the minimum notice period")
    @ApiResponse(responseCode = "503", description = "The appointment is locked by another request and could not be changed within 3 seconds; retry shortly (Retry-After: 1)")
    @PatchMapping("/{id}")
    public ResponseEntity<AppointmentResponse> updateAppointment(@PathVariable UUID id,
                                                                 @Valid @RequestBody AppointmentUpdateRequest request,
                                                                 @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        UUID patientId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(appointmentService.updateAppointment(id, request, patientId));
    }

    @Operation(summary = "Cancel one of the authenticated patient's appointments")
    @ApiResponse(responseCode = "200", description = "Appointment cancelled; cancelling an already-cancelled appointment is a no-op and returns the current state without a new event")
    @ApiResponse(responseCode = "403", description = "The token is not a patient token, or the resource belongs to another patient")
    @ApiResponse(responseCode = "404", description = "Appointment not found")
    @ApiResponse(responseCode = "503", description = "The appointment is locked by another request and could not be cancelled within 3 seconds; retry shortly (Retry-After: 1). The cancel is idempotent, so retrying is safe")
    @PutMapping("/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancelAppointment(@PathVariable UUID id,
                                                                 @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        UUID patientId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.OK)
                .body(appointmentService.cancelAppointment(id, patientId));
    }
}
