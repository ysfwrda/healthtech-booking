package com.healthtech.doctor.controller;

import com.healthtech.doctor.dto.SpecialtyDto;
import com.healthtech.doctor.service.SpecialtyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SpecialtyController.class)
class SpecialtyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SpecialtyService specialtyService;

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
