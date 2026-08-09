package com.healthtech.patient.controller;

import com.healthtech.patient.domain.InsuranceType;
import com.healthtech.patient.dto.PatientResponse;
import com.healthtech.patient.exception.PatientAccessDeniedException;
import com.healthtech.patient.exception.PatientNotFoundException;
import com.healthtech.patient.security.SecurityConfig;
import com.healthtech.patient.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /api/patients/{id} requires authentication under the real SecurityConfig. Spring
// Security's default fallback (deny all, HTTP Basic) also applies to any @WebMvcTest slice
// that does not load a SecurityConfig, now that spring-boot-starter-oauth2-resource-server
// is on the classpath, which would happen to produce the same 401 for a no-token request but
// silently NOT enforce the real rules. Importing the real SecurityConfig (with a mocked
// JwtDecoder so no key material is needed, since JwtDecoder now lives in the separate
// JwtDecoderConfig that SecurityConfig no longer pulls in) makes this test actually exercise
// the application's own authorization rules, consistent with DoctorControllerTest.
@WebMvcTest(PatientController.class)
@Import(SecurityConfig.class)
class PatientControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientService patientService;

    @MockitoBean
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    // The real mapping from SecurityConfig, not a reimplementation, so these tests exercise
    // exactly what hasRole("PATIENT") sees in production and can't drift from it.
    private static final Converter<Jwt, Collection<GrantedAuthority>> ROLE_AUTHORITIES =
            new SecurityConfig().roleAuthoritiesConverter();

    // ── GET /api/patients/{id} ────────────────────────────────────────────────

    @Test
    void getPatientProfile_noToken_returns401() throws Exception {
        // A request without a Bearer token should be rejected before reaching the controller.
        mockMvc.perform(get("/api/patients/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        verify(patientService, never()).getPatientProfileById(any(), any());
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

        when(patientService.getPatientProfileById(patientId, patientId)).thenReturn(patientResponse);

        mockMvc.perform(get("/api/patients/" + patientId)
                        .with(jwt().jwt(builder -> builder.subject(patientId.toString()).claim("role", "PATIENT"))
                                .authorities(ROLE_AUTHORITIES)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("John"));
    }

    @Test
    void getPatientProfile_validTokenMismatchedId_returns403() throws Exception {
        // JWT subject is a different UUID than the path variable: the service's ownership
        // check must reject this, and the GlobalExceptionHandler must map it to 403.
        UUID patientId = UUID.randomUUID();
        UUID anotherId = UUID.randomUUID();
        when(patientService.getPatientProfileById(patientId, anotherId))
                .thenThrow(new PatientAccessDeniedException(patientId));
        mockMvc.perform(get("/api/patients/" + patientId)
                        .with(jwt().jwt(builder -> builder.subject(anotherId.toString()).claim("role", "PATIENT"))
                                .authorities(ROLE_AUTHORITIES)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPatientProfile_validTokenPatientNotFound_returns404() throws Exception {
        UUID patientId = UUID.randomUUID();
        when(patientService.getPatientProfileById(patientId, patientId)).thenThrow(new PatientNotFoundException(patientId));
        mockMvc.perform(get("/api/patients/" + patientId)
                        .with(jwt().jwt(builder -> builder.subject(patientId.toString()).claim("role", "PATIENT"))
                                .authorities(ROLE_AUTHORITIES)))
                .andExpect(status().isNotFound());
    }

    // ── Role enforcement (ADR-004) ──────────────────────────────────────────────
    // These exercise SecurityConfig's own hasRole("PATIENT") rule, not PatientController's
    // ownership logic covered above. A DOCTOR token is signed under the same shared key pair,
    // so authenticity alone can't tell the two apart; only the role claim, mapped to a ROLE_
    // authority by SecurityConfig.roleAuthoritiesConverter(), does.

    @Test
    void getPatientProfile_wrongRoleToken_returns403() throws Exception {
        UUID patientId = UUID.randomUUID();
        mockMvc.perform(get("/api/patients/" + patientId)
                        .with(jwt().jwt(builder -> builder.subject(patientId.toString()).claim("role", "DOCTOR"))
                                .authorities(ROLE_AUTHORITIES)))
                .andExpect(status().isForbidden());
        verify(patientService, never()).getPatientProfileById(any(), any());
    }
}
