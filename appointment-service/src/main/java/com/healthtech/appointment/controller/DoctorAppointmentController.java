package com.healthtech.appointment.controller;

import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.dto.DoctorAppointmentResponse;
import com.healthtech.appointment.dto.DoctorAppointmentUpdateRequest;
import com.healthtech.appointment.service.AppointmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Doctor appointments")
@RequestMapping("/api/appointments/doctor")
@RequiredArgsConstructor
public class DoctorAppointmentController {
    private final AppointmentService appointmentService;

    @Operation(summary = "List the authenticated doctor's appointments, earliest first",
            description = "All filters are optional. from and to are whole days, both inclusive; status may be repeated.")
    @ApiResponse(responseCode = "200", description = "The doctor's appointments, possibly empty",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DoctorAppointmentResponse[].class)))
    @ApiResponse(responseCode = "400", description = "from is after to, or a parameter is malformed")
    @GetMapping
    public ResponseEntity<List<DoctorAppointmentResponse>> getMyAppointments(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "status", required = false) List<AppointmentStatus> statuses,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        UUID doctorId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(appointmentService.getAppointmentsForDoctor(doctorId, from, to, statuses));
    }

    @Operation(summary = "Confirm a pending appointment of the authenticated doctor",
            description = "The patient is notified. Confirming an already confirmed appointment is a no-op that returns the "
                    + "current state without a new event.")
    @ApiResponse(responseCode = "200", description = "Appointment confirmed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppointmentResponse.class)))
    @ApiResponse(responseCode = "403", description = "The token is not a doctor token, or the appointment belongs to another doctor")
    @ApiResponse(responseCode = "404", description = "Appointment not found")
    @ApiResponse(responseCode = "409", description = "The appointment is cancelled, or no longer fits the doctor's availability")
    @ApiResponse(responseCode = "503", description = "The appointment is locked by another request and could not be confirmed within 3 seconds; retry shortly (Retry-After: 1)")
    @PutMapping("/{id}/confirm")
    public ResponseEntity<AppointmentResponse> confirmAppointment(@PathVariable UUID id,
                                                                  @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(appointmentService.confirmAppointment(id, UUID.fromString(jwt.getSubject())));
    }

    @Operation(summary = "Cancel an appointment of the authenticated doctor",
            description = "Allowed at any time, with no notice period. The patient is notified. Cancelling an already "
                    + "cancelled appointment is a no-op that returns the current state without a new event.")
    @ApiResponse(responseCode = "200", description = "Appointment cancelled",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppointmentResponse.class)))
    @ApiResponse(responseCode = "403", description = "The token is not a doctor token, or the appointment belongs to another doctor")
    @ApiResponse(responseCode = "404", description = "Appointment not found")
    @ApiResponse(responseCode = "503", description = "The appointment is locked by another request and could not be cancelled within 3 seconds; retry shortly (Retry-After: 1). The cancel is idempotent, so retrying is safe")
    @PutMapping("/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancelAppointment(@PathVariable UUID id,
                                                                 @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(appointmentService.cancelAppointmentAsDoctor(id, UUID.fromString(jwt.getSubject())));
    }

    @Operation(summary = "Change the purpose and/or time of an appointment of the authenticated doctor",
            description = "Partial update: send only the fields to change (at least one). The status stays as it is and the "
                    + "patient is notified. There is no notice period. The new time must be a free slot in the future, inside the "
                    + "doctor's opening hours and not on a vacation day.")
    @ApiResponse(responseCode = "200", description = "Appointment changed (or already as requested)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppointmentResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failed (no field given), slot not aligned to the slot grid, outside the doctor's opening hours, on a vacation day, or in the past")
    @ApiResponse(responseCode = "403", description = "The token is not a doctor token, or the appointment belongs to another doctor")
    @ApiResponse(responseCode = "404", description = "Appointment not found, or the doctor is not known to the appointment service")
    @ApiResponse(responseCode = "409", description = "Slot already booked, or the appointment is cancelled")
    @ApiResponse(responseCode = "503", description = "The appointment or the doctor's schedule is locked by another request and could not be changed within 3 seconds; retry shortly (Retry-After: 1)")
    @PatchMapping("/{id}")
    public ResponseEntity<AppointmentResponse> updateAppointment(@PathVariable UUID id,
                                                                 @Valid @RequestBody DoctorAppointmentUpdateRequest request,
                                                                 @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(appointmentService.updateAppointmentAsDoctor(id, request, UUID.fromString(jwt.getSubject())));
    }
}
