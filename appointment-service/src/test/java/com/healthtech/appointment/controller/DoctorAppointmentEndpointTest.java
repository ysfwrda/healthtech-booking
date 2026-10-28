package com.healthtech.appointment.controller;

import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.ConflictReason;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.dto.DoctorAppointmentResponse;
import com.healthtech.appointment.dto.DoctorAppointmentUpdateRequest;
import com.healthtech.appointment.exception.AppointmentAccessDeniedException;
import com.healthtech.appointment.exception.AppointmentNotChangeableException;
import com.healthtech.appointment.exception.AppointmentNotConfirmableException;
import com.healthtech.appointment.exception.AppointmentNotFoundException;
import com.healthtech.appointment.exception.InvalidDateRangeException;
import com.healthtech.appointment.exception.SlotAlreadyBookedException;
import com.healthtech.appointment.exception.SlotOnVacationException;
import com.healthtech.appointment.security.SecurityConfig;
import com.healthtech.appointment.service.AppointmentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// The doctor endpoints with the real SecurityConfig and GlobalExceptionHandler.
@WebMvcTest(DoctorAppointmentController.class)
@Import(SecurityConfig.class)
class DoctorAppointmentEndpointTest {

    private static final Converter<Jwt, Collection<GrantedAuthority>> ROLE_AUTHORITIES =
            new SecurityConfig().roleAuthoritiesConverter();
    private static final String BASE = "/api/appointments/doctor";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppointmentService appointmentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID doctorId = UUID.randomUUID();
    private final UUID appointmentId = UUID.randomUUID();

    private RequestPostProcessor doctorToken() {
        return jwt().jwt(b -> b.subject(doctorId.toString()).claim("role", "DOCTOR")).authorities(ROLE_AUTHORITIES);
    }

    private RequestPostProcessor patientToken() {
        return jwt().jwt(b -> b.subject(UUID.randomUUID().toString()).claim("role", "PATIENT")).authorities(ROLE_AUTHORITIES);
    }

    // -- security ----------------------------------------------------------------

    @Test
    void list_noToken_returns401() throws Exception {
        // Act & Assert
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void list_patientToken_returns403() throws Exception {
        // Act & Assert
        mockMvc.perform(get(BASE).with(patientToken())).andExpect(status().isForbidden());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void confirm_patientToken_returns403() throws Exception {
        // Act & Assert
        mockMvc.perform(put(BASE + "/{id}/confirm", appointmentId).with(patientToken())).andExpect(status().isForbidden());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void cancel_patientToken_returns403() throws Exception {
        // Act & Assert
        mockMvc.perform(put(BASE + "/{id}/cancel", appointmentId).with(patientToken())).andExpect(status().isForbidden());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void change_patientToken_returns403() throws Exception {
        // Act & Assert
        mockMvc.perform(patch(BASE + "/{id}", appointmentId).with(patientToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"FOLLOW_UP\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void confirm_noToken_returns401() throws Exception {
        // Act & Assert
        mockMvc.perform(put(BASE + "/{id}/confirm", appointmentId)).andExpect(status().isUnauthorized());
    }

    @Test
    void patientEndpoint_doctorToken_returns403() throws Exception {
        // Act & Assert: a doctor cannot use the patient's cancel either
        mockMvc.perform(put("/api/appointments/{id}/cancel", appointmentId).with(doctorToken()))
                .andExpect(status().isForbidden());
    }

    // -- list --------------------------------------------------------------------

    @Test
    void list_doctorToken_passesTheTokenSubjectAndFiltersAndReturnsTheBody() throws Exception {
        // Arrange
        when(appointmentService.getAppointmentsForDoctor(eq(doctorId), eq(LocalDate.of(2026, 10, 1)),
                eq(LocalDate.of(2026, 10, 31)), any())).thenReturn(List.of(DoctorAppointmentResponse.builder()
                .id(appointmentId).patientName("Jane Doe").patientEmail("jane@example.com")
                .status(AppointmentStatus.PENDING).conflictReason(ConflictReason.DOCTOR_VACATION).build()));

        // Act & Assert
        mockMvc.perform(get(BASE).with(doctorToken())
                        .param("from", "2026-10-01").param("to", "2026-10-31")
                        .param("status", "PENDING").param("status", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].patientName").value("Jane Doe"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].conflictReason").value("DOCTOR_VACATION"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<AppointmentStatus>> statuses = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(appointmentService).getAppointmentsForDoctor(eq(doctorId), any(), any(), statuses.capture());
        org.assertj.core.api.Assertions.assertThat(statuses.getValue())
                .containsExactlyInAnyOrder(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED);
    }

    @Test
    void list_noFilters_passesNulls() throws Exception {
        // Arrange
        when(appointmentService.getAppointmentsForDoctor(eq(doctorId), isNull(), isNull(), isNull())).thenReturn(List.of());

        // Act & Assert
        mockMvc.perform(get(BASE).with(doctorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void list_fromAfterTo_returns400ProblemDetail() throws Exception {
        // Arrange
        when(appointmentService.getAppointmentsForDoctor(any(), any(), any(), any())).thenThrow(new InvalidDateRangeException());

        // Act & Assert
        mockMvc.perform(get(BASE).with(doctorToken()).param("from", "2026-10-31").param("to", "2026-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Date Range"));
    }

    @Test
    void list_malformedDate_returns400() throws Exception {
        // Act & Assert
        mockMvc.perform(get(BASE).with(doctorToken()).param("from", "yesterday"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(appointmentService);
    }

    @Test
    void list_unknownStatus_returns400() throws Exception {
        // Act & Assert
        mockMvc.perform(get(BASE).with(doctorToken()).param("status", "DONE"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(appointmentService);
    }

    // -- confirm -----------------------------------------------------------------

    @Test
    void confirm_doctorToken_passesTheTokenSubjectAndReturns200() throws Exception {
        // Arrange
        when(appointmentService.confirmAppointment(appointmentId, doctorId))
                .thenReturn(AppointmentResponse.builder().id(appointmentId).status(AppointmentStatus.CONFIRMED).build());

        // Act & Assert
        mockMvc.perform(put(BASE + "/{id}/confirm", appointmentId).with(doctorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void confirm_notConfirmable_returns409ProblemDetail() throws Exception {
        // Arrange
        when(appointmentService.confirmAppointment(appointmentId, doctorId))
                .thenThrow(new AppointmentNotConfirmableException(appointmentId, "it is cancelled"));

        // Act & Assert
        mockMvc.perform(put(BASE + "/{id}/confirm", appointmentId).with(doctorToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Appointment Not Confirmable"));
    }

    @Test
    void confirm_anotherDoctorsAppointment_returns403ProblemDetail() throws Exception {
        // Arrange
        when(appointmentService.confirmAppointment(appointmentId, doctorId))
                .thenThrow(new AppointmentAccessDeniedException(appointmentId));

        // Act & Assert
        mockMvc.perform(put(BASE + "/{id}/confirm", appointmentId).with(doctorToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Not Resource Owner"));
    }

    @Test
    void confirm_unknownAppointment_returns404() throws Exception {
        // Arrange
        when(appointmentService.confirmAppointment(appointmentId, doctorId))
                .thenThrow(new AppointmentNotFoundException("Appointment not found: " + appointmentId));

        // Act & Assert
        mockMvc.perform(put(BASE + "/{id}/confirm", appointmentId).with(doctorToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Appointment Not Found"));
    }

    @Test
    void confirm_lockWaitTimesOut_returns503WithRetryAfter() throws Exception {
        // Arrange
        when(appointmentService.confirmAppointment(appointmentId, doctorId))
                .thenThrow(new QueryTimeoutException("lock wait timed out"));

        // Act & Assert
        mockMvc.perform(put(BASE + "/{id}/confirm", appointmentId).with(doctorToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.title").value("Appointment Busy"));
    }

    @Test
    void confirm_malformedId_returns400() throws Exception {
        // Act & Assert
        mockMvc.perform(put(BASE + "/not-a-uuid/confirm").with(doctorToken())).andExpect(status().isBadRequest());
    }

    // -- cancel ------------------------------------------------------------------

    @Test
    void cancel_doctorToken_passesTheTokenSubjectAndReturns200() throws Exception {
        // Arrange
        when(appointmentService.cancelAppointmentAsDoctor(appointmentId, doctorId))
                .thenReturn(AppointmentResponse.builder().id(appointmentId).status(AppointmentStatus.CANCELLED).build());

        // Act & Assert
        mockMvc.perform(put(BASE + "/{id}/cancel", appointmentId).with(doctorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancel_anotherDoctorsAppointment_returns403() throws Exception {
        // Arrange
        when(appointmentService.cancelAppointmentAsDoctor(appointmentId, doctorId))
                .thenThrow(new AppointmentAccessDeniedException(appointmentId));

        // Act & Assert
        mockMvc.perform(put(BASE + "/{id}/cancel", appointmentId).with(doctorToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancel_unknownAppointment_returns404() throws Exception {
        // Arrange
        when(appointmentService.cancelAppointmentAsDoctor(appointmentId, doctorId))
                .thenThrow(new AppointmentNotFoundException("Appointment not found: " + appointmentId));

        // Act & Assert
        mockMvc.perform(put(BASE + "/{id}/cancel", appointmentId).with(doctorToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancel_lockNotAcquired_returns503WithRetryAfter() throws Exception {
        // Arrange
        when(appointmentService.cancelAppointmentAsDoctor(appointmentId, doctorId))
                .thenThrow(new CannotAcquireLockException("could not obtain lock"));

        // Act & Assert
        mockMvc.perform(put(BASE + "/{id}/cancel", appointmentId).with(doctorToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
    }

    // -- change ------------------------------------------------------------------

    @Test
    void change_validBody_passesTheTokenSubjectAndReturns200() throws Exception {
        // Arrange
        when(appointmentService.updateAppointmentAsDoctor(eq(appointmentId), any(DoctorAppointmentUpdateRequest.class), eq(doctorId)))
                .thenReturn(AppointmentResponse.builder().id(appointmentId).build());

        // Act & Assert
        mockMvc.perform(patch(BASE + "/{id}", appointmentId).with(doctorToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FOLLOW_UP\",\"dateTime\":\"2026-10-12T09:30:00\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<DoctorAppointmentUpdateRequest> request = ArgumentCaptor.forClass(DoctorAppointmentUpdateRequest.class);
        verify(appointmentService).updateAppointmentAsDoctor(eq(appointmentId), request.capture(), eq(doctorId));
        org.assertj.core.api.Assertions.assertThat(request.getValue().getDateTime())
                .isEqualTo(LocalDateTime.of(2026, 10, 12, 9, 30));
    }

    @Test
    void change_emptyBody_returns400() throws Exception {
        // Act & Assert
        mockMvc.perform(patch(BASE + "/{id}", appointmentId).with(doctorToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
        verify(appointmentService, never()).updateAppointmentAsDoctor(any(), any(), any());
    }

    @Test
    void change_onlyNotes_returns400BecauseDoctorsCannotEditNotes() throws Exception {
        // Act & Assert
        mockMvc.perform(patch(BASE + "/{id}", appointmentId).with(doctorToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"rewritten\"}"))
                .andExpect(status().isBadRequest());
        verify(appointmentService, never()).updateAppointmentAsDoctor(any(), any(), any());
    }

    @Test
    void change_unknownType_returns400() throws Exception {
        // Act & Assert
        mockMvc.perform(patch(BASE + "/{id}", appointmentId).with(doctorToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"SURGERY\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void change_slotOnVacation_returns400ProblemDetail() throws Exception {
        // Arrange
        when(appointmentService.updateAppointmentAsDoctor(any(), any(), any())).thenThrow(new SlotOnVacationException());

        // Act & Assert
        mockMvc.perform(patch(BASE + "/{id}", appointmentId).with(doctorToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dateTime\":\"2026-12-24T09:00:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Slot On Vacation"));
    }

    @Test
    void change_slotTaken_returns409ProblemDetail() throws Exception {
        // Arrange
        when(appointmentService.updateAppointmentAsDoctor(any(), any(), any()))
                .thenThrow(new SlotAlreadyBookedException(doctorId, LocalDateTime.of(2026, 12, 24, 9, 0)));

        // Act & Assert
        mockMvc.perform(patch(BASE + "/{id}", appointmentId).with(doctorToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dateTime\":\"2026-12-24T09:00:00\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Slot Already Booked"));
    }

    @Test
    void change_cancelledAppointment_returns409ProblemDetail() throws Exception {
        // Arrange
        when(appointmentService.updateAppointmentAsDoctor(any(), any(), any()))
                .thenThrow(new AppointmentNotChangeableException(appointmentId));

        // Act & Assert
        mockMvc.perform(patch(BASE + "/{id}", appointmentId).with(doctorToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"FOLLOW_UP\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Appointment Not Changeable"));
    }

    @Test
    void change_anotherDoctorsAppointment_returns403() throws Exception {
        // Arrange
        when(appointmentService.updateAppointmentAsDoctor(any(), any(), any()))
                .thenThrow(new AppointmentAccessDeniedException(appointmentId));

        // Act & Assert
        mockMvc.perform(patch(BASE + "/{id}", appointmentId).with(doctorToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"FOLLOW_UP\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void change_lockWaitTimesOut_returns503WithRetryAfter() throws Exception {
        // Arrange
        when(appointmentService.updateAppointmentAsDoctor(any(), any(), any()))
                .thenThrow(new QueryTimeoutException("lock wait timed out"));

        // Act & Assert
        mockMvc.perform(patch(BASE + "/{id}", appointmentId).with(doctorToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"FOLLOW_UP\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
    }
}
