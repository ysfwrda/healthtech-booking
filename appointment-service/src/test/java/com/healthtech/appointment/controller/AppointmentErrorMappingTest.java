package com.healthtech.appointment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.exception.SlotInPastException;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Slice test for how GlobalExceptionHandler maps booking rule failures to problem details.
@WebMvcTest(AppointmentController.class)
@Import(SecurityConfig.class)
class AppointmentErrorMappingTest {

    private static final Converter<Jwt, Collection<GrantedAuthority>> ROLE_AUTHORITIES =
            new SecurityConfig().roleAuthoritiesConverter();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AppointmentService appointmentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void bookAppointment_slotInPast_returns400WithSlotInPastProblemDetail() throws Exception {
        // Arrange
        AppointmentRequest request = AppointmentRequest.builder()
                .doctorId(UUID.randomUUID())
                .dateTime(LocalDateTime.now().plusDays(1))
                .type(AppointmentType.INITIAL_CONSULTATION)
                .build();
        when(appointmentService.bookAppointment(any(), any())).thenThrow(new SlotInPastException());

        // Act & Assert
        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()).claim("role", "PATIENT"))
                                .authorities(ROLE_AUTHORITIES)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Slot In The Past"))
                .andExpect(jsonPath("$.status").value(400));
    }
}
