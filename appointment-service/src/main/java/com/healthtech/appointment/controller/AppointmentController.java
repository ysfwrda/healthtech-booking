package com.healthtech.appointment.controller;

import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.exception.WrongTokenTypeException;
import com.healthtech.appointment.service.AppointmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
public class AppointmentController {
    private final AppointmentService appointmentService;

    @PostMapping
    public ResponseEntity<AppointmentResponse> bookAppointment(@Valid @RequestBody AppointmentRequest request,
                                                               @AuthenticationPrincipal Jwt jwt) {
        if (!"PATIENT".equals(jwt.getClaimAsString("role"))) {
            throw new WrongTokenTypeException();
        }

        UUID patientId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(appointmentService.bookAppointment(request, patientId));
    }

    @GetMapping
    public ResponseEntity<List<AppointmentResponse>> getMyAppointments(@AuthenticationPrincipal Jwt jwt) {
        if (!"PATIENT".equals(jwt.getClaimAsString("role"))) {
            throw new WrongTokenTypeException();
        }

        UUID patientId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(appointmentService.getAppointmentsForPatient(patientId));
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancelAppointment(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        if (!"PATIENT".equals(jwt.getClaimAsString("role"))) {
            throw new WrongTokenTypeException();
        }

        UUID patientId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.OK)
                .body(appointmentService.cancelAppointment(id, patientId));
    }
}
