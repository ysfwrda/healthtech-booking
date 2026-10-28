package com.healthtech.appointment.controller;

import com.healthtech.appointment.exception.SlotOnVacationException;
import com.healthtech.appointment.security.SecurityConfig;
import com.healthtech.appointment.service.AppointmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Collection;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Responses the patient endpoints gained with vacations and the doctor-row lock.
@WebMvcTest(AppointmentController.class)
@Import(SecurityConfig.class)
class AppointmentVacationAndLockErrorResponseTest {

    private static final Converter<Jwt, Collection<GrantedAuthority>> ROLE_AUTHORITIES =
            new SecurityConfig().roleAuthoritiesConverter();
    private static final String BOOKING = "{\"doctorId\":\"" + UUID.randomUUID()
            + "\",\"dateTime\":\"2030-03-18T09:00:00\",\"type\":\"FOLLOW_UP\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppointmentService appointmentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private RequestPostProcessor patientToken() {
        return jwt().jwt(b -> b.subject(UUID.randomUUID().toString()).claim("role", "PATIENT")).authorities(ROLE_AUTHORITIES);
    }

    @Test
    void bookAppointment_slotOnVacation_returns400ProblemDetail() throws Exception {
        // Arrange
        when(appointmentService.bookAppointment(any(), any())).thenThrow(new SlotOnVacationException());

        // Act & Assert
        mockMvc.perform(post("/api/appointments").with(patientToken())
                        .contentType(MediaType.APPLICATION_JSON).content(BOOKING))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Slot On Vacation"));
    }

    @Test
    void bookAppointment_doctorRowLockWaitTimesOut_returns503WithRetryAfter() throws Exception {
        // Arrange
        when(appointmentService.bookAppointment(any(), any())).thenThrow(new QueryTimeoutException("lock wait timed out"));

        // Act & Assert
        mockMvc.perform(post("/api/appointments").with(patientToken())
                        .contentType(MediaType.APPLICATION_JSON).content(BOOKING))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.title").value("Appointment Busy"));
    }

    @Test
    void updateAppointment_slotOnVacation_returns400ProblemDetail() throws Exception {
        // Arrange
        when(appointmentService.updateAppointment(any(), any(), any())).thenThrow(new SlotOnVacationException());

        // Act & Assert
        mockMvc.perform(patch("/api/appointments/{id}", UUID.randomUUID()).with(patientToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dateTime\":\"2030-03-18T09:00:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Slot On Vacation"));
    }
}
