package com.healthtech.appointment.controller;

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
    @PostMapping
    public ResponseEntity<AppointmentResponse> bookAppointment(@Valid @RequestBody AppointmentRequest request,
                                                               @AuthenticationPrincipal Jwt jwt) {
        UUID patientId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(appointmentService.bookAppointment(request, patientId));
    }

    @Operation(summary = "List the authenticated patient's appointments")
    @GetMapping
    public ResponseEntity<List<AppointmentResponse>> getMyAppointments(@AuthenticationPrincipal Jwt jwt) {
        UUID patientId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(appointmentService.getAppointmentsForPatient(patientId));
    }

    @Operation(summary = "Cancel one of the authenticated patient's appointments")
    @PutMapping("/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancelAppointment(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID patientId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.OK)
                .body(appointmentService.cancelAppointment(id, patientId));
    }
}
