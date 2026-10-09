package com.healthtech.appointment.controller;

import com.healthtech.appointment.exception.AppointmentAccessDeniedException;
import com.healthtech.appointment.exception.AppointmentNotFoundException;
import com.healthtech.appointment.security.SecurityConfig;
import com.healthtech.appointment.service.AppointmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Collection;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Error responses of PUT /api/appointments/{id}/cancel as produced by GlobalExceptionHandler.
@WebMvcTest(AppointmentController.class)
@Import(SecurityConfig.class)
class AppointmentCancelErrorResponseTest {

    private static final Converter<Jwt, Collection<GrantedAuthority>> ROLE_AUTHORITIES =
            new SecurityConfig().roleAuthoritiesConverter();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppointmentService appointmentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private RequestPostProcessor patientToken(UUID patientId) {
        return jwt().jwt(builder -> builder.subject(patientId.toString()).claim("role", "PATIENT"))
                .authorities(ROLE_AUTHORITIES);
    }

    @Test
    void cancelAppointment_queryTimeout_returns503WithRetryAfter() throws Exception {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        when(appointmentService.cancelAppointment(appointmentId, patientId))
                .thenThrow(new QueryTimeoutException("lock wait timed out"));

        // Act & Assert
        mockMvc.perform(put("/api/appointments/{id}/cancel", appointmentId).with(patientToken(patientId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.title").value("Appointment Busy"))
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void cancelAppointment_lockNotAcquired_returns503WithRetryAfter() throws Exception {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        when(appointmentService.cancelAppointment(appointmentId, patientId))
                .thenThrow(new CannotAcquireLockException("could not obtain lock"));

        // Act & Assert
        mockMvc.perform(put("/api/appointments/{id}/cancel", appointmentId).with(patientToken(patientId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.title").value("Appointment Busy"));
    }

    @Test
    void cancelAppointment_ownedByAnotherPatient_returns403() throws Exception {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        when(appointmentService.cancelAppointment(appointmentId, patientId))
                .thenThrow(new AppointmentAccessDeniedException(appointmentId));

        // Act & Assert
        mockMvc.perform(put("/api/appointments/{id}/cancel", appointmentId).with(patientToken(patientId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Not Resource Owner"));
    }

    @Test
    void cancelAppointment_unknownId_returns404() throws Exception {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        when(appointmentService.cancelAppointment(appointmentId, patientId))
                .thenThrow(new AppointmentNotFoundException("Appointment not found: " + appointmentId));

        // Act & Assert
        mockMvc.perform(put("/api/appointments/{id}/cancel", appointmentId).with(patientToken(patientId)))
                .andExpect(status().isNotFound());
    }
}
