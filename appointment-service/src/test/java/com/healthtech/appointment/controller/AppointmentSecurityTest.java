package com.healthtech.appointment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.security.SecurityConfig;
import com.healthtech.appointment.service.AppointmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Role enforcement for /api/appointments/** moved from AppointmentController (a manual
// "role == PATIENT" check throwing WrongTokenTypeException) to SecurityConfig's
// anyRequest().hasRole("PATIENT") rule (ADR-004). This is the MockMvc-level replacement for
// the three DOCTOR-token cases that used to live in AppointmentControllerTest as unit tests
// against the now-removed exception; AppointmentIntegrationTest additionally proves the same
// rejection end-to-end with a real token signed under the shared key pair.
@WebMvcTest(AppointmentController.class)
@Import(SecurityConfig.class)
class AppointmentSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AppointmentService appointmentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // The real mapping from SecurityConfig, not a reimplementation, so these tests exercise
    // exactly what hasRole("PATIENT") sees in production and can't drift from it.
    private static final Converter<Jwt, Collection<GrantedAuthority>> ROLE_AUTHORITIES =
            new SecurityConfig().roleAuthoritiesConverter();

    private AppointmentRequest sampleRequest() {
        return AppointmentRequest.builder()
                .doctorId(UUID.randomUUID())
                .dateTime(LocalDateTime.now().plusDays(1))
                .type(AppointmentType.INITIAL_CONSULTATION)
                .build();
    }

    @Test
    void bookAppointment_doctorToken_returns403() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest()))
                        .with(jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()).claim("role", "DOCTOR"))
                                .authorities(ROLE_AUTHORITIES)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void getMyAppointments_doctorToken_returns403() throws Exception {
        mockMvc.perform(get("/api/appointments")
                        .with(jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()).claim("role", "DOCTOR"))
                                .authorities(ROLE_AUTHORITIES)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void cancelAppointment_doctorToken_returns403() throws Exception {
        mockMvc.perform(put("/api/appointments/{id}/cancel", UUID.randomUUID())
                        .with(jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()).claim("role", "DOCTOR"))
                                .authorities(ROLE_AUTHORITIES)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(appointmentService);
    }
}
