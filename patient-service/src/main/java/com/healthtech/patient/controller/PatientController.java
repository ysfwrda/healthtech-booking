package com.healthtech.patient.controller;

import com.healthtech.patient.dto.PatientResponse;
import com.healthtech.patient.service.PatientService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/patients")
@RequiredArgsConstructor
public class PatientController {
    private static final Logger log = LoggerFactory.getLogger(PatientController.class);

    private final PatientService patientService;

    @GetMapping("/{id}")
    public ResponseEntity<PatientResponse> getPatientProfile(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt){
        if(!jwt.getSubject().equals(id.toString())){
            log.warn("Forbidden: requester {} attempted to access patientId {}, status 403", jwt.getSubject(), id);
            return new ResponseEntity<>(HttpStatus.FORBIDDEN); // TODO: do this in service, same as in Appointment Service
        }

        return ResponseEntity.ok(patientService.getPatientProfileById(id));
    }
}
