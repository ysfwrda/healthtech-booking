package com.healthtech.appointment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.service.AppointmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AppointmentController.class)
class AppointmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AppointmentService appointmentService;

    @Test
    void bookAppointment_shouldReturn201WithResponseBody() throws Exception {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        AppointmentRequest request = AppointmentRequest.builder()
                .patientId(UUID.randomUUID())
                .doctorId(UUID.randomUUID())
                .dateTime(LocalDateTime.now().plusDays(1))
                .duration(30)
                .type(AppointmentType.INITIAL_CONSULTATION)
                .build();

        AppointmentResponse response = AppointmentResponse.builder()
                .id(appointmentId)
                .status(AppointmentStatus.PENDING)
                .type(AppointmentType.INITIAL_CONSULTATION)
                .duration(30)
                .build();

        when(appointmentService.bookAppointment(any(AppointmentRequest.class))).thenReturn(response);

        // Act and Assert
        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(appointmentId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.type").value("INITIAL_CONSULTATION"))
                .andExpect(jsonPath("$.duration").value(30));

        verify(appointmentService).bookAppointment(any(AppointmentRequest.class));
    }

    @Test
    void bookAppointment_shouldPassRequestBodyToService() throws Exception {
        // Arrange
        AppointmentRequest request = AppointmentRequest.builder()
                .patientId(UUID.randomUUID())
                .doctorId(UUID.randomUUID())
                .dateTime(LocalDateTime.of(2026, 8, 1, 10, 0))
                .duration(45)
                .type(AppointmentType.FOLLOW_UP)
                .notes("Follow-up visit")
                .build();

        when(appointmentService.bookAppointment(any())).thenReturn(AppointmentResponse.builder()
                .id(UUID.randomUUID())
                .status(AppointmentStatus.PENDING)
                .build());

        // Act and Assert
        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        verify(appointmentService).bookAppointment(any(AppointmentRequest.class));
    }

    @Test
    void cancelAppointment_shouldReturn200WithCancelledStatus() throws Exception {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        AppointmentResponse response = AppointmentResponse.builder()
                .id(appointmentId)
                .status(AppointmentStatus.CANCELLED)
                .type(AppointmentType.INITIAL_CONSULTATION)
                .build();

        when(appointmentService.cancelAppointment(appointmentId)).thenReturn(response);

        // Act and Assert
        mockMvc.perform(put("/api/appointments/{id}/cancel", appointmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appointmentId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelAppointment_whenNotFound_shouldPropagateExceptionFromService() {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        when(appointmentService.cancelAppointment(appointmentId))
                .thenThrow(new RuntimeException("Appointment not found: " + appointmentId));

        // Act and Assert
        assertThatThrownBy(() ->
            mockMvc.perform(put("/api/appointments/{id}/cancel", appointmentId))
        ).hasMessageContaining("Appointment not found: " + appointmentId);
    }
}
