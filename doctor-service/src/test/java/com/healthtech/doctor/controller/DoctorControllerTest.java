package com.healthtech.doctor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.doctor.dto.DoctorResponse;
import com.healthtech.doctor.exception.DoctorNotFoundException;
import com.healthtech.doctor.security.SecurityConfig;
import com.healthtech.doctor.service.DoctorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// GET /api/doctors/** is permitAll in SecurityConfig. Doctor creation now lives behind the
// public /api/doctors/register endpoint owned by DoctorAuthController, not this controller;
// see DoctorAuthControllerTest / DoctorIntegrationTest for that coverage.
@WebMvcTest(DoctorController.class)
@Import(SecurityConfig.class)
class DoctorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DoctorService doctorService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // The real mapping from SecurityConfig, not a reimplementation, so these tests exercise
    // exactly what hasRole("DOCTOR") sees in production and can't drift from it.
    private static final Converter<Jwt, Collection<GrantedAuthority>> ROLE_AUTHORITIES =
            new SecurityConfig().roleAuthoritiesConverter();

    private final UUID doctorId = UUID.randomUUID();

    private DoctorResponse sampleResponse() {
        return DoctorResponse.builder()
                .id(doctorId)
                .firstName("Anna")
                .lastName("Mueller")
                .email("anna.mueller@example.com")
                .phoneNumber("+49123456789")
                .registeredAt(LocalDateTime.now())
                .build();
    }

    // -- GET /api/doctors/{id} ------------------------------------------------

    @Test
    void getDoctorById_existingId_returns200WithBody() throws Exception {
        // Arrange
        when(doctorService.getDoctorById(doctorId)).thenReturn(sampleResponse());

        // Act & Assert: no token is sent, and the request must reach the controller and
        // succeed under the real permitAll rule. A missing or wrongly governed
        // SecurityFilterChain would answer with 401 and a WWW-Authenticate: Basic header
        // before the controller is ever invoked; asserting the header is absent is a
        // direct regression guard against that failure mode.
        mockMvc.perform(get("/api/doctors/{id}", doctorId))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.id").value(doctorId.toString()))
                .andExpect(jsonPath("$.lastName").value("Mueller"));

        org.mockito.Mockito.verify(doctorService).getDoctorById(doctorId);
    }

    @Test
    void getDoctorById_notFound_returns404() throws Exception {
        // Arrange
        when(doctorService.getDoctorById(doctorId)).thenThrow(new DoctorNotFoundException(doctorId));

        // Act & Assert
        mockMvc.perform(get("/api/doctors/{id}", doctorId))
                .andExpect(status().isNotFound());
    }

    // -- GET /api/doctors -------------------------------------------------------

    @Test
    void findDoctors_noFilters_returns200() throws Exception {
        // Arrange
        when(doctorService.findDoctors(null, null)).thenReturn(java.util.List.of());

        // Act & Assert
        mockMvc.perform(get("/api/doctors"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    // ── Role enforcement (ADR-004) ──────────────────────────────────────────────
    // DoctorController currently exposes only the public GET endpoints above; there is no
    // protected doctor-self-management endpoint yet. These probe SecurityConfig's own
    // anyRequest().hasRole("DOCTOR") rule directly via PUT /api/doctors/{id} - a method the
    // controller does not map, but a path SecurityConfig's GET-only public permit does not
    // cover either, so it falls to anyRequest() exactly as a future protected endpoint at
    // this path would. A DOCTOR token is signed under the same shared key pair as a PATIENT
    // token (ADR-004), so authenticity alone can't tell the two apart; only the role claim,
    // mapped to a ROLE_ authority by SecurityConfig.roleAuthoritiesConverter(), does.

    @Test
    void putDoctor_noToken_returns401() throws Exception {
        mockMvc.perform(put("/api/doctors/{id}", doctorId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putDoctor_doctorToken_isNotRejectedBySecurity() throws Exception {
        // GET is mapped at this path, so once security lets a DOCTOR-role PUT through, MVC
        // answers 405 (method not mapped) rather than 401/403 - that's what this asserts.
        mockMvc.perform(put("/api/doctors/{id}", doctorId)
                        .with(jwt().jwt(builder -> builder.subject(doctorId.toString()).claim("role", "DOCTOR"))
                                .authorities(ROLE_AUTHORITIES)))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void putDoctor_wrongRoleToken_returns403() throws Exception {
        mockMvc.perform(put("/api/doctors/{id}", doctorId)
                        .with(jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()).claim("role", "PATIENT"))
                                .authorities(ROLE_AUTHORITIES)))
                .andExpect(status().isForbidden());
    }
}
