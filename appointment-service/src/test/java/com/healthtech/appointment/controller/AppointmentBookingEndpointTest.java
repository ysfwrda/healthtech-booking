package com.healthtech.appointment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.exception.DoctorNotFoundException;
import com.healthtech.appointment.exception.PatientNotFoundException;
import com.healthtech.appointment.security.SecurityConfig;
import com.healthtech.appointment.service.AppointmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Request validation and read-model error responses of POST /api/appointments.
@WebMvcTest(AppointmentController.class)
@Import(SecurityConfig.class)
class AppointmentBookingEndpointTest {

    private static final Converter<Jwt, Collection<GrantedAuthority>> ROLE_AUTHORITIES =
            new SecurityConfig().roleAuthoritiesConverter();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AppointmentService appointmentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID patientId = UUID.randomUUID();
    private final UUID doctorId = UUID.randomUUID();

    private AppointmentRequest.AppointmentRequestBuilder validRequest() {
        return AppointmentRequest.builder()
                .doctorId(doctorId)
                .dateTime(LocalDateTime.of(2026, 10, 12, 9, 0))
                .type(AppointmentType.INITIAL_CONSULTATION);
    }

    private ResultActions postAsPatient(AppointmentRequest request) throws Exception {
        return mockMvc.perform(post("/api/appointments")
                .with(jwt().jwt(builder -> builder.subject(patientId.toString()).claim("role", "PATIENT"))
                        .authorities(ROLE_AUTHORITIES))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    // --- request validation ---

    @Test
    void bookAppointment_missingDoctorId_returns400ValidationError() throws Exception {
        // Act & Assert
        postAsPatient(validRequest().doctorId(null).build())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors.doctorId").exists());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void bookAppointment_missingDateTime_returns400ValidationError() throws Exception {
        // Act & Assert
        postAsPatient(validRequest().dateTime(null).build())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors.dateTime").exists());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void bookAppointment_missingType_returns400ValidationError() throws Exception {
        // Act & Assert
        postAsPatient(validRequest().type(null).build())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors.type").exists());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void bookAppointment_notesOf500Characters_returns201() throws Exception {
        // Arrange
        when(appointmentService.bookAppointment(any(), any())).thenReturn(AppointmentResponse.builder().build());

        // Act & Assert
        postAsPatient(validRequest().notes("a".repeat(500)).build())
                .andExpect(status().isCreated());
    }

    @Test
    void bookAppointment_notesOf501Characters_returns400ValidationError() throws Exception {
        // Act & Assert
        postAsPatient(validRequest().notes("a".repeat(501)).build())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors.notes").exists());
        verifyNoInteractions(appointmentService);
    }

    // --- read-model lookups ---

    @Test
    void bookAppointment_patientNotInReadModel_returns404PatientNotFound() throws Exception {
        // Arrange
        when(appointmentService.bookAppointment(any(), any())).thenThrow(new PatientNotFoundException(patientId));

        // Act & Assert
        postAsPatient(validRequest().build())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Patient Not Found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void bookAppointment_doctorNotInReadModel_returns404DoctorNotFound() throws Exception {
        // Arrange
        when(appointmentService.bookAppointment(any(), any())).thenThrow(new DoctorNotFoundException(doctorId));

        // Act & Assert
        postAsPatient(validRequest().build())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Doctor Not Found"))
                .andExpect(jsonPath("$.status").value(404));
    }
}
