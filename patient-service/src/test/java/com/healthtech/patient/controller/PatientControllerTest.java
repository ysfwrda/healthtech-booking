package com.healthtech.patient.controller;

import com.healthtech.patient.domain.InsuranceType;
import com.healthtech.patient.dto.PatientResponse;
import com.healthtech.patient.exception.PatientNotFoundException;
import com.healthtech.patient.service.PatientService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.healthtech.patient.security.SecurityConfig;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PatientController.class)
@Import(SecurityConfig.class)
class PatientControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientService patientService;

    @MockitoBean
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    // ── GET /api/patients/{id} ────────────────────────────────────────────────

    @Test
    void getPatientProfile_noToken_returns401() throws Exception {
        // A request without a Bearer token should be rejected before reaching the controller.
        mockMvc.perform(get("/api/patients/"+ UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        verify(patientService, never()).getPatientProfileById(any());
    }

    @Test
    void getPatientProfile_validTokenMatchingId_returns200WithBody() throws Exception {
        UUID patientId = UUID.randomUUID();
        PatientResponse patientResponse = PatientResponse.builder()
                .id(patientId)
                .firstName("John")
                .lastName("Doe")
                .email("john.doe@gmail.com")
                .dateOfBirth(LocalDate.of(1990, 1, 15))
                .registeredAt(LocalDateTime.now())
                .insuranceType(InsuranceType.STATUTORY)
                .build();

        when(patientService.getPatientProfileById(patientId)).thenReturn(patientResponse);

        mockMvc.perform(get("/api/patients/"+ patientId)
                        .with(jwt().jwt(builder -> builder.subject(patientId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("John"));
    }

    @Test
    void getPatientProfile_validTokenMismatchedId_returns403() throws Exception {
        // JWT subject is a different UUID than the path variable — controller must return 403.
        UUID patientId = UUID.randomUUID();
        UUID anotherId = UUID.randomUUID();
        mockMvc.perform(get("/api/patients/"+ patientId)
                        .with(jwt().jwt(builder -> builder.subject(anotherId.toString()))))
                .andExpect(status().isForbidden());
        verify(patientService, never()).getPatientProfileById(any());
    }

    @Disabled("pending error-handling pass - exception mapping")
    @Test
    void getPatientProfile_validTokenPatientNotFound_returns404() throws Exception {
        UUID patientId = UUID.randomUUID();
        when(patientService.getPatientProfileById(patientId)).thenThrow(new PatientNotFoundException(patientId));
        mockMvc.perform(get("/api/patients/"+ patientId)
                        .with(jwt().jwt(builder -> builder.subject(patientId.toString()))))
                .andExpect(status().isNotFound());
        // JWT subject matches the path id, but patientService throws PatientNotFoundException.
        // Assert 404 is returned (requires a @ControllerAdvice / exception handler).
    }
}
