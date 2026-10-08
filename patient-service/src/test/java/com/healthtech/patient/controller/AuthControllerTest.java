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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Imports the real SecurityConfig (with a mocked JwtDecoder, as in PatientControllerTest) so
// the permitAll rule for POST /api/auth/register is exercised rather than Spring's default
// deny-all fallback.
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

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

    // ── POST /api/auth/register ───────────────────────────────────────────────

    @Test
    void register_passwordTooShort_returns400WithPasswordError() throws Exception {
        // Arrange: a 7-character password is one below the minimum of 8.
        // Act + Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("\"1234567\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
        verify(authService, never()).register(any(RegisterRequest.class));
    }

    @Test
    void register_passwordExactlyMinimumLength_returns201() throws Exception {
        // Arrange: an 8-character password is the shortest accepted value.
        when(authService.register(any(RegisterRequest.class)))
                .thenReturn(AuthResponse.builder().token("jwt").expiresIn(3600L).username("max.mustermann").build());

        // Act + Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("\"12345678\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("jwt"));
        verify(authService).register(any(RegisterRequest.class));
    }

    @Test
    void register_passwordTooLong_returns400WithPasswordError() throws Exception {
        // Arrange: 73 characters is one above the BCrypt-safe maximum of 72.
        // Act + Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("\"" + "a".repeat(73) + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
        verify(authService, never()).register(any(RegisterRequest.class));
    }

    @Test
    void register_passwordExactlyMaximumLength_returns201() throws Exception {
        // Arrange: 72 characters is the longest accepted value.
        when(authService.register(any(RegisterRequest.class)))
                .thenReturn(AuthResponse.builder().token("jwt").expiresIn(3600L).username("max.mustermann").build());

        // Act + Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("\"" + "a".repeat(72) + "\"")))
                .andExpect(status().isCreated());
        verify(authService).register(any(RegisterRequest.class));
    }

    @Test
    void register_blankPassword_returns400WithPasswordError() throws Exception {
        // Arrange: a blank password violates @NotBlank (and @Size).
        // Act + Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("\"   \"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
        verify(authService, never()).register(any(RegisterRequest.class));
    }
}
