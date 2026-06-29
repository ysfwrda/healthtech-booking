package com.healthtech.doctor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.doctor.domain.Language;
import com.healthtech.doctor.dto.AddressDto;
import com.healthtech.doctor.dto.CreateDoctorRequest;
import com.healthtech.doctor.dto.DoctorResponse;
import com.healthtech.doctor.dto.OpeningHoursDto;
import com.healthtech.doctor.exception.DoctorNotFoundException;
import com.healthtech.doctor.service.DoctorService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DoctorController.class)
class DoctorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DoctorService doctorService;

    private final UUID doctorId = UUID.randomUUID();
    private final UUID specialtyId = UUID.randomUUID();

    private CreateDoctorRequest validRequest() {
        return CreateDoctorRequest.builder()
                .firstName("Anna")
                .lastName("Mueller")
                .email("anna.mueller@example.com")
                .phoneNumber("+49123456789")
                .address(AddressDto.builder()
                        .street("Hauptstrasse")
                        .houseNumber("1")
                        .postalCode("10115")
                        .city("Berlin")
                        .country("DE")
                        .build())
                .specialtyIds(Set.of(specialtyId))
                .openingHours(Set.of(OpeningHoursDto.builder()
                        .dayOfWeek(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(8, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build()))
                .languages(Set.of(Language.GERMAN))
                .build();
    }

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

    // -- POST /api/doctors ----------------------------------------------------

    @Test
    void createDoctor_validRequest_returns201WithBody() throws Exception {
        // Arrange
        when(doctorService.createDoctor(any(CreateDoctorRequest.class))).thenReturn(sampleResponse());

        // Act & Assert
        mockMvc.perform(post("/api/doctors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(doctorId.toString()))
                .andExpect(jsonPath("$.firstName").value("Anna"))
                .andExpect(jsonPath("$.email").value("anna.mueller@example.com"));

        verify(doctorService).createDoctor(any(CreateDoctorRequest.class));
    }

    @Test
    void createDoctor_blankFirstName_returns400() throws Exception {
        // Arrange: firstName violates @NotBlank
        CreateDoctorRequest invalid = validRequest();
        invalid.setFirstName("");

        // Act & Assert
        mockMvc.perform(post("/api/doctors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());

        verify(doctorService, never()).createDoctor(any());
    }

    @Test
    void createDoctor_invalidEmailFormat_returns400() throws Exception {
        // Arrange: email violates @Email
        CreateDoctorRequest invalid = validRequest();
        invalid.setEmail("not-an-email");

        // Act & Assert
        mockMvc.perform(post("/api/doctors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());

        verify(doctorService, never()).createDoctor(any());
    }

    @Test
    void createDoctor_missingAddress_returns400() throws Exception {
        // Arrange: address is @NotNull
        CreateDoctorRequest invalid = validRequest();
        invalid.setAddress(null);

        // Act & Assert
        mockMvc.perform(post("/api/doctors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());

        verify(doctorService, never()).createDoctor(any());
    }

    // -- GET /api/doctors/{id} ------------------------------------------------

    @Test
    void getDoctorById_existingId_returns200WithBody() throws Exception {
        // Arrange
        when(doctorService.getDoctorById(doctorId)).thenReturn(sampleResponse());

        // Act & Assert
        mockMvc.perform(get("/api/doctors/{id}", doctorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(doctorId.toString()))
                .andExpect(jsonPath("$.lastName").value("Mueller"));

        verify(doctorService).getDoctorById(doctorId);
    }

    @Disabled("pending error-handling pass: DoctorNotFoundException must be mapped to 404 by a @ControllerAdvice")
    @Test
    void getDoctorById_notFound_returns404() throws Exception {
        // Arrange
        when(doctorService.getDoctorById(doctorId)).thenThrow(new DoctorNotFoundException(doctorId));

        // Act & Assert
        mockMvc.perform(get("/api/doctors/{id}", doctorId))
                .andExpect(status().isNotFound());
    }
}
