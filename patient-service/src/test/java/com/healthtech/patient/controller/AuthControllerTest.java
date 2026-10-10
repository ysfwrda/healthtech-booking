package com.healthtech.patient.controller;

import com.healthtech.patient.dto.AuthResponse;
import com.healthtech.patient.dto.RegisterRequest;
import com.healthtech.patient.security.SecurityConfig;
import com.healthtech.patient.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Slice test: real SecurityConfig, so the permitAll rule for /api/auth/register applies.
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static String registerJson(String password) {
        return """
                {
                  "firstName": "Max",
                  "lastName": "Mustermann",
                  "username": "max.mustermann",
                  "password": %s,
                  "dateOfBirth": "1990-05-17",
                  "email": "max.mustermann@example.com",
                  "insuranceType": "STATUTORY"
                }
                """.formatted(password);
    }

    // POST /api/auth/register

    @Test
    void register_passwordTooShort_returns400WithPasswordError() throws Exception {
        // Arrange
        String password = "\"1234567\"";

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(password)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
        verify(authService, never()).register(any(RegisterRequest.class));
    }

    @Test
    void register_passwordExactlyMinimumLength_returns201() throws Exception {
        // Arrange
        String password = "\"12345678\"";
        when(authService.register(any(RegisterRequest.class)))
                .thenReturn(AuthResponse.builder().token("jwt").expiresIn(3600L).username("max.mustermann").build());

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("jwt"));
        verify(authService).register(any(RegisterRequest.class));
    }

    @Test
    void register_passwordTooLong_returns400WithPasswordError() throws Exception {
        // Arrange
        String password = "\"" + "a".repeat(73) + "\"";

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(password)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
        verify(authService, never()).register(any(RegisterRequest.class));
    }

    @Test
    void register_passwordExactlyMaximumLength_returns201() throws Exception {
        // Arrange
        String password = "\"" + "a".repeat(72) + "\"";
        when(authService.register(any(RegisterRequest.class)))
                .thenReturn(AuthResponse.builder().token("jwt").expiresIn(3600L).username("max.mustermann").build());

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(password)))
                .andExpect(status().isCreated());
        verify(authService).register(any(RegisterRequest.class));
    }

    @Test
    void register_blankPassword_returns400WithPasswordError() throws Exception {
        // Arrange
        String password = "\"   \"";

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(password)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
        verify(authService, never()).register(any(RegisterRequest.class));
    }
}
