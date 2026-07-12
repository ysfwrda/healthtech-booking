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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.when;
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
}
