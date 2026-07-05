package com.healthtech.appointment.controller;

import com.healthtech.appointment.dto.AvailableSlotsResponse;
import com.healthtech.appointment.security.SecurityConfig;
import com.healthtech.appointment.service.AppointmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /api/availability/** is permitAll in SecurityConfig, but Spring Security's default
// fallback (deny all) applies to any @WebMvcTest slice that does not load a SecurityConfig,
// now that spring-boot-starter-oauth2-resource-server is on the classpath. Importing the
// real SecurityConfig (with a mocked JwtDecoder so no key material is needed) restores the
// permitAll rule for this public endpoint without requiring a token in these tests.
@WebMvcTest(AvailabilityController.class)
@Import(SecurityConfig.class)
class AvailabilityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppointmentService appointmentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void getAvailability_validRequest_shouldReturn200WithMockedBodyAndParsedDate() throws Exception {
        // Arrange
        UUID doctorId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 13);
        LocalDateTime firstSlot = LocalDateTime.of(2026, 7, 13, 9, 0);
        LocalDateTime secondSlot = LocalDateTime.of(2026, 7, 13, 9, 30);
        AvailableSlotsResponse response = AvailableSlotsResponse.builder()
                .doctorId(doctorId)
                .date(date)
                .availableSlots(List.of(firstSlot, secondSlot))
                .build();

        when(appointmentService.getAvailableSlots(doctorId, date)).thenReturn(response);

        // Act and Assert
        mockMvc.perform(get("/api/availability")
                        .param("doctorId", doctorId.toString())
                        .param("date", "2026-07-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctorId").value(doctorId.toString()))
                .andExpect(jsonPath("$.date").value("2026-07-13"))
                .andExpect(jsonPath("$.availableSlots.length()").value(2))
                .andExpect(jsonPath("$.availableSlots[0]").value(firstSlot.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
                .andExpect(jsonPath("$.availableSlots[1]").value(secondSlot.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));

        // Confirms the "date" query param was bound into a real LocalDate (not just
        // forwarded as a raw string) by checking the exact LocalDate the service received.
        verify(appointmentService).getAvailableSlots(doctorId, date);
    }

    @Test
    void getAvailability_malformedDate_shouldReturn400() throws Exception {
        // Arrange
        UUID doctorId = UUID.randomUUID();

        // Act and Assert
        mockMvc.perform(get("/api/availability")
                        .param("doctorId", doctorId.toString())
                        .param("date", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAvailability_malformedDoctorId_shouldReturn400() throws Exception {
        // Act and Assert
        mockMvc.perform(get("/api/availability")
                        .param("doctorId", "not-a-uuid")
                        .param("date", "2026-07-13"))
                .andExpect(status().isBadRequest());
    }

    // Note: an unknown-doctor case (service throws UnknownDoctorException) is not
    // covered here. There is no @RestControllerAdvice mapping for it yet, so today the
    // exception would surface as an unhandled 500, not a 404. This becomes a valid test
    // once the exception handler exists; do not add one here just to make this pass.
}
