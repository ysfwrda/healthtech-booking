package com.healthtech.doctor.controller;

import com.healthtech.doctor.dto.SpecialtyDto;
import com.healthtech.doctor.security.SecurityConfig;
import com.healthtech.doctor.service.SpecialtyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /api/specialties is permitAll in SecurityConfig, but Spring Security's default fallback
// (deny all) applies to any @WebMvcTest slice that does not load a SecurityConfig, now that
// spring-boot-starter-oauth2-resource-server is on the classpath. Importing the real
// SecurityConfig (with a mocked JwtDecoder so no key material is needed) restores the permitAll
// rule for this public endpoint without requiring a token in these tests.
@WebMvcTest(SpecialtyController.class)
@Import(SecurityConfig.class)
class SpecialtyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SpecialtyService specialtyService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // -- GET /api/specialties -------------------------------------------------

    @Test
    void getAllSpecialties_withResults_returns200AndList() throws Exception {
        // Arrange
        List<SpecialtyDto> specialties = List.of(
                new SpecialtyDto(UUID.randomUUID(), "Cardiology"),
                new SpecialtyDto(UUID.randomUUID(), "Neurology")
        );
        when(specialtyService.getAllSpecialties()).thenReturn(specialties);

        // Act & Assert
        mockMvc.perform(get("/api/specialties"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Cardiology"));

        verify(specialtyService).getAllSpecialties();
    }

    @Test
    void getAllSpecialties_emptyList_returns200AndEmptyArray() throws Exception {
        // Arrange
        when(specialtyService.getAllSpecialties()).thenReturn(List.of());

        // Act & Assert
        mockMvc.perform(get("/api/specialties"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(specialtyService).getAllSpecialties();
    }
}
