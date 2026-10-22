package com.healthtech.doctor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.doctor.domain.Language;
import com.healthtech.doctor.dto.AddressDto;
import com.healthtech.doctor.dto.DoctorAuthResponse;
import com.healthtech.doctor.dto.DoctorRegistrationRequest;
import com.healthtech.doctor.dto.OpeningHoursDto;
import com.healthtech.doctor.security.SecurityConfig;
import com.healthtech.doctor.service.DoctorAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Password validation of POST /api/doctors/register, under the real SecurityConfig.
@WebMvcTest(DoctorAuthController.class)
@Import(SecurityConfig.class)
class DoctorRegisterPasswordTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DoctorAuthService doctorAuthService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private ResultActions postRegisterWithPassword(String password) throws Exception {
        DoctorRegistrationRequest request = DoctorRegistrationRequest.builder()
                .firstName("Anna").lastName("Schmidt").email("anna.schmidt@example.com")
                .password(password)
                .phoneNumber("+49 30 1234567")
                .address(AddressDto.builder()
                        .street("Hauptstrasse").houseNumber("12").postalCode("10115")
                        .city("Berlin").country("Germany")
                        .build())
                .specialtyIds(Set.of(UUID.randomUUID()))
                .languages(Set.of(Language.GERMAN))
                .openingHours(Set.of(OpeningHoursDto.builder()
                        .dayOfWeek(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build()))
                .build();
        return mockMvc.perform(post("/api/doctors/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private void stubSuccessfulRegistration() {
        when(doctorAuthService.register(any(DoctorRegistrationRequest.class)))
                .thenReturn(DoctorAuthResponse.builder().id(UUID.randomUUID()).token("jwt").expiresIn(3600L)
                        .email("anna.schmidt@example.com").build());
    }

    @Test
    void register_password73Bytes_returns400WithPasswordError() throws Exception {
        // Act & Assert
        postRegisterWithPassword("a".repeat(73))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
        verify(doctorAuthService, never()).register(any(DoctorRegistrationRequest.class));
    }

    @Test
    void register_password72Bytes_returns201() throws Exception {
        // Arrange
        stubSuccessfulRegistration();

        // Act & Assert
        postRegisterWithPassword("a".repeat(72))
                .andExpect(status().isCreated());
        verify(doctorAuthService).register(any(DoctorRegistrationRequest.class));
    }

    @Test
    void register_passwordOf37TwoByteCharacters_returns400WithPasswordError() throws Exception {
        // Act & Assert: 37 characters, but 74 bytes
        postRegisterWithPassword("ä".repeat(37))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
        verify(doctorAuthService, never()).register(any(DoctorRegistrationRequest.class));
    }

    @Test
    void register_passwordOf36TwoByteCharacters_returns201() throws Exception {
        // Arrange
        stubSuccessfulRegistration();

        // Act & Assert: 72 bytes
        postRegisterWithPassword("ä".repeat(36))
                .andExpect(status().isCreated());
        verify(doctorAuthService).register(any(DoctorRegistrationRequest.class));
    }
}
