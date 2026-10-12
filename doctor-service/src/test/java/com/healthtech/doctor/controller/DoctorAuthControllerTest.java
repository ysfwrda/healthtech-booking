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

// Request validation of POST /api/doctors/register, under the real SecurityConfig (the endpoint is permitAll).
@WebMvcTest(DoctorAuthController.class)
@Import(SecurityConfig.class)
class DoctorAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DoctorAuthService doctorAuthService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static DoctorRegistrationRequest.DoctorRegistrationRequestBuilder validRequest() {
        return DoctorRegistrationRequest.builder()
                .firstName("Anna")
                .lastName("Schmidt")
                .email("anna.schmidt@example.com")
                .password("ChangeMe123!")
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
                        .build()));
    }

    private ResultActions postRegister(DoctorRegistrationRequest request) throws Exception {
        return mockMvc.perform(post("/api/doctors/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private void stubSuccessfulRegistration() {
        when(doctorAuthService.register(any(DoctorRegistrationRequest.class)))
                .thenReturn(DoctorAuthResponse.builder().id(UUID.randomUUID()).token("jwt").expiresIn(3600L)
                        .email("anna.schmidt@example.com").build());
    }

    // --- password ---

    @Test
    void register_passwordTooShort_returns400WithPasswordError() throws Exception {
        // Arrange
        DoctorRegistrationRequest request = validRequest().password("1234567").build();

        // Act & Assert
        postRegister(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors.password").exists());
        verify(doctorAuthService, never()).register(any());
    }

    @Test
    void register_passwordExactlyMinimumLength_returns201() throws Exception {
        // Arrange
        DoctorRegistrationRequest request = validRequest().password("12345678").build();
        stubSuccessfulRegistration();

        // Act & Assert
        postRegister(request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("jwt"));
        verify(doctorAuthService).register(any());
    }

    @Test
    void register_blankPassword_returns400WithPasswordError() throws Exception {
        // Arrange: eight spaces satisfy @Size(min = 8), so only @NotBlank rejects it
        DoctorRegistrationRequest request = validRequest().password("        ").build();

        // Act & Assert
        postRegister(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors.password").exists());
        verify(doctorAuthService, never()).register(any());
    }

    // --- required sets ---

    @Test
    void register_emptySpecialtyIds_returns400WithSpecialtyIdsError() throws Exception {
        // Arrange
        DoctorRegistrationRequest request = validRequest().specialtyIds(Set.of()).build();

        // Act & Assert
        postRegister(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors.specialtyIds").exists());
        verify(doctorAuthService, never()).register(any());
    }

    @Test
    void register_emptyLanguages_returns400WithLanguagesError() throws Exception {
        // Arrange
        DoctorRegistrationRequest request = validRequest().languages(Set.of()).build();

        // Act & Assert
        postRegister(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors.languages").exists());
        verify(doctorAuthService, never()).register(any());
    }

    @Test
    void register_emptyOpeningHours_returns400WithOpeningHoursError() throws Exception {
        // Arrange
        DoctorRegistrationRequest request = validRequest().openingHours(Set.of()).build();

        // Act & Assert
        postRegister(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors.openingHours").exists());
        verify(doctorAuthService, never()).register(any());
    }
}
