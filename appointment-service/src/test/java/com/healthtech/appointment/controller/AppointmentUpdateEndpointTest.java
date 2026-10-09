package com.healthtech.appointment.controller;

import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.dto.AppointmentUpdateRequest;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.exception.AppointmentAccessDeniedException;
import com.healthtech.appointment.exception.AppointmentNotChangeableException;
import com.healthtech.appointment.exception.AppointmentNotFoundException;
import com.healthtech.appointment.exception.ChangeWindowClosedException;
import com.healthtech.appointment.exception.DoctorNotFoundException;
import com.healthtech.appointment.exception.OutsideOpeningHoursException;
import com.healthtech.appointment.exception.SlotAlreadyBookedException;
import com.healthtech.appointment.exception.SlotInPastException;
import com.healthtech.appointment.exception.SlotNotAlignedException;
import com.healthtech.appointment.security.SecurityConfig;
import com.healthtech.appointment.service.AppointmentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Request validation, authorization and error responses of PATCH /api/appointments/{id}.
@WebMvcTest(AppointmentController.class)
@Import(SecurityConfig.class)
class AppointmentUpdateEndpointTest {

    private static final Converter<Jwt, Collection<GrantedAuthority>> ROLE_AUTHORITIES =
            new SecurityConfig().roleAuthoritiesConverter();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppointmentService appointmentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID appointmentId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    private RequestPostProcessor patientToken() {
        return jwt().jwt(builder -> builder.subject(patientId.toString()).claim("role", "PATIENT"))
                .authorities(ROLE_AUTHORITIES);
    }

    private ResultActions patchAsPatient(String body) throws Exception {
        return mockMvc.perform(patch("/api/appointments/{id}", appointmentId)
                .with(patientToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // --- success ---

    @Test
    void updateAppointment_validBody_returns200AndPassesTokenSubjectAndBody() throws Exception {
        // Arrange
        AppointmentResponse response = AppointmentResponse.builder().id(appointmentId).type(AppointmentType.FOLLOW_UP).build();
        when(appointmentService.updateAppointment(any(), any(), any())).thenReturn(response);

        // Act
        ResultActions result = patchAsPatient("{\"type\":\"FOLLOW_UP\",\"dateTime\":\"2026-10-12T09:30:00\",\"notes\":\"n\"}");

        // Assert
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appointmentId.toString()))
                .andExpect(jsonPath("$.type").value("FOLLOW_UP"));
        ArgumentCaptor<AppointmentUpdateRequest> captor = ArgumentCaptor.forClass(AppointmentUpdateRequest.class);
        verify(appointmentService).updateAppointment(org.mockito.ArgumentMatchers.eq(appointmentId), captor.capture(),
                org.mockito.ArgumentMatchers.eq(patientId));
        assertThat(captor.getValue().getType()).isEqualTo(AppointmentType.FOLLOW_UP);
        assertThat(captor.getValue().getDateTime()).isEqualTo(LocalDateTime.of(2026, 10, 12, 9, 30));
        assertThat(captor.getValue().getNotes()).isEqualTo("n");
    }

    // --- authentication and authorization ---

    @Test
    void updateAppointment_noToken_returns401() throws Exception {
        // Act & Assert
        mockMvc.perform(patch("/api/appointments/{id}", appointmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FOLLOW_UP\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void updateAppointment_doctorToken_returns403() throws Exception {
        // Act & Assert
        mockMvc.perform(patch("/api/appointments/{id}", appointmentId)
                        .with(jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()).claim("role", "DOCTOR"))
                                .authorities(ROLE_AUTHORITIES))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FOLLOW_UP\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(appointmentService);
    }

    // --- request validation ---

    @Test
    void updateAppointment_emptyObject_returns400() throws Exception {
        // Act & Assert
        patchAsPatient("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors.anyFieldPresent").exists());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void updateAppointment_notesOf500Characters_isAccepted() throws Exception {
        // Arrange
        when(appointmentService.updateAppointment(any(), any(), any())).thenReturn(AppointmentResponse.builder().build());

        // Act & Assert
        patchAsPatient("{\"notes\":\"" + "a".repeat(500) + "\"}").andExpect(status().isOk());
    }

    @Test
    void updateAppointment_notesOf501Characters_returns400() throws Exception {
        // Act & Assert
        patchAsPatient("{\"notes\":\"" + "a".repeat(501) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.notes").exists());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void updateAppointment_emptyNotesAlone_isAccepted() throws Exception {
        // Arrange
        when(appointmentService.updateAppointment(any(), any(), any())).thenReturn(AppointmentResponse.builder().build());

        // Act & Assert
        patchAsPatient("{\"notes\":\"\"}").andExpect(status().isOk());
    }

    @Test
    void updateAppointment_unknownType_returns400() throws Exception {
        // Act & Assert
        patchAsPatient("{\"type\":\"HAIRCUT\"}").andExpect(status().isBadRequest());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void updateAppointment_malformedDateTime_returns400() throws Exception {
        // Act & Assert
        patchAsPatient("{\"dateTime\":\"tomorrow\"}").andExpect(status().isBadRequest());
        verifyNoInteractions(appointmentService);
    }

    // --- error responses ---

    private void givenServiceThrows(RuntimeException exception) {
        when(appointmentService.updateAppointment(any(), any(), any())).thenThrow(exception);
    }

    @Test
    void updateAppointment_cancelledAppointment_returns409NotChangeable() throws Exception {
        // Arrange
        givenServiceThrows(new AppointmentNotChangeableException(appointmentId));

        // Act & Assert
        patchAsPatient("{\"type\":\"FOLLOW_UP\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Appointment Not Changeable"));
    }

    @Test
    void updateAppointment_insideNoticePeriod_returns409ChangeWindowClosed() throws Exception {
        // Arrange
        givenServiceThrows(new ChangeWindowClosedException(48));

        // Act & Assert
        patchAsPatient("{\"type\":\"FOLLOW_UP\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Change Window Closed"))
                .andExpect(jsonPath("$.detail").value("An appointment can only be changed up to 48 hours before it starts"));
    }

    @Test
    void updateAppointment_slotTaken_returns409SlotAlreadyBooked() throws Exception {
        // Arrange
        givenServiceThrows(new SlotAlreadyBookedException(UUID.randomUUID(), LocalDateTime.of(2026, 10, 12, 9, 30)));

        // Act & Assert
        patchAsPatient("{\"dateTime\":\"2026-10-12T09:30:00\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Slot Already Booked"));
    }

    @Test
    void updateAppointment_slotInThePast_returns400() throws Exception {
        // Arrange
        givenServiceThrows(new SlotInPastException());

        // Act & Assert
        patchAsPatient("{\"dateTime\":\"2020-01-01T09:00:00\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Slot In The Past"));
    }

    @Test
    void updateAppointment_slotNotAligned_returns400() throws Exception {
        // Arrange
        givenServiceThrows(new SlotNotAlignedException());

        // Act & Assert
        patchAsPatient("{\"dateTime\":\"2026-10-12T09:15:00\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Slot Not Aligned"));
    }

    @Test
    void updateAppointment_outsideOpeningHours_returns400() throws Exception {
        // Arrange
        givenServiceThrows(new OutsideOpeningHoursException());

        // Act & Assert
        patchAsPatient("{\"dateTime\":\"2026-10-12T20:00:00\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Outside Opening Hours"));
    }

    @Test
    void updateAppointment_ownedByAnotherPatient_returns403() throws Exception {
        // Arrange
        givenServiceThrows(new AppointmentAccessDeniedException(appointmentId));

        // Act & Assert
        patchAsPatient("{\"type\":\"FOLLOW_UP\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Not Resource Owner"));
    }

    @Test
    void updateAppointment_unknownAppointment_returns404() throws Exception {
        // Arrange
        givenServiceThrows(new AppointmentNotFoundException("Appointment not found: " + appointmentId));

        // Act & Assert
        patchAsPatient("{\"type\":\"FOLLOW_UP\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Appointment Not Found"));
    }

    @Test
    void updateAppointment_doctorUnknown_returns404() throws Exception {
        // Arrange
        givenServiceThrows(new DoctorNotFoundException(UUID.randomUUID()));

        // Act & Assert
        patchAsPatient("{\"dateTime\":\"2026-10-12T09:30:00\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Doctor Not Found"));
    }

    @Test
    void updateAppointment_lockWaitTimesOut_returns503WithRetryAfter() throws Exception {
        // Arrange
        givenServiceThrows(new QueryTimeoutException("lock wait timed out"));

        // Act & Assert
        patchAsPatient("{\"type\":\"FOLLOW_UP\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.title").value("Appointment Busy"));
    }
}
