package com.healthtech.appointment.controller;

import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.exception.AppointmentNotFoundException;
import com.healthtech.appointment.exception.WrongTokenTypeException;
import com.healthtech.appointment.service.AppointmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// A plain Mockito unit test rather than @WebMvcTest: the controller now requires an
// @AuthenticationPrincipal Jwt, and a mocked Jwt passed directly as a method argument is
// enough to exercise the controller's own authorization logic without standing up a real
// Spring Security filter chain (JWT signature validation is Spring Security's concern,
// covered by SecurityConfig, not this class).
@ExtendWith(MockitoExtension.class)
class AppointmentControllerTest {

    @Mock
    private AppointmentService appointmentService;

    @Mock
    private Jwt jwt;

    private AppointmentController appointmentController;

    @BeforeEach
    void setUp() {
        appointmentController = new AppointmentController(appointmentService);
    }

    private AppointmentRequest sampleRequest() {
        return AppointmentRequest.builder()
                .doctorId(UUID.randomUUID())
                .dateTime(LocalDateTime.now().plusDays(1))
                .type(AppointmentType.INITIAL_CONSULTATION)
                .build();
    }

    @Test
    void bookAppointment_patientToken_shouldReturn201WithResponseBody() {
        // Arrange
        UUID patientId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        AppointmentRequest request = sampleRequest();

        when(jwt.getClaimAsString("role")).thenReturn("PATIENT");
        when(jwt.getSubject()).thenReturn(patientId.toString());

        AppointmentResponse response = AppointmentResponse.builder()
                .id(appointmentId)
                .status(AppointmentStatus.CONFIRMED)
                .type(AppointmentType.INITIAL_CONSULTATION)
                .duration(30)
                .build();

        when(appointmentService.bookAppointment(eq(request), eq(patientId))).thenReturn(response);

        // Act
        ResponseEntity<AppointmentResponse> result = appointmentController.bookAppointment(request, jwt);

        // Assert
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(response);
        assertThat(result.getBody().getId()).isEqualTo(appointmentId);
        assertThat(result.getBody().getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
    }

    @Test
    void bookAppointment_patientToken_shouldCallServiceWithPatientIdFromTokenSubject() {
        // Arrange
        UUID patientId = UUID.randomUUID();
        AppointmentRequest request = sampleRequest();

        when(jwt.getClaimAsString("role")).thenReturn("PATIENT");
        when(jwt.getSubject()).thenReturn(patientId.toString());
        when(appointmentService.bookAppointment(any(), any())).thenReturn(AppointmentResponse.builder().build());

        // Act
        appointmentController.bookAppointment(request, jwt);

        // Assert: the id passed to the service is exactly the token's subject, proving
        // identity comes from the token and not from the request body.
        ArgumentCaptor<UUID> patientIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(appointmentService).bookAppointment(eq(request), patientIdCaptor.capture());
        assertThat(patientIdCaptor.getValue()).isEqualTo(patientId);
    }

    @Test
    void bookAppointment_nonPatientToken_shouldThrowWrongTokenExceptionAndNeverCallService() {
        // Arrange
        AppointmentRequest request = sampleRequest();
        when(jwt.getClaimAsString("role")).thenReturn("DOCTOR");

        // Act and Assert: the critical "rejected" direction of the role check.
        assertThatThrownBy(() -> appointmentController.bookAppointment(request, jwt))
                .isInstanceOf(WrongTokenTypeException.class);

        verifyNoInteractions(appointmentService);
    }

    @Test
    void cancelAppointment_shouldReturn200AndPassPatientIdFromTokenSubject() {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        when(jwt.getClaimAsString("role")).thenReturn("PATIENT");
        when(jwt.getSubject()).thenReturn(patientId.toString());

        AppointmentResponse response = AppointmentResponse.builder()
                .id(appointmentId)
                .status(AppointmentStatus.CANCELLED)
                .type(AppointmentType.INITIAL_CONSULTATION)
                .build();

        when(appointmentService.cancelAppointment(appointmentId, patientId)).thenReturn(response);

        // Act
        ResponseEntity<AppointmentResponse> result = appointmentController.cancelAppointment(appointmentId, jwt);

        // Assert
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().getId()).isEqualTo(appointmentId);
        assertThat(result.getBody().getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        verify(appointmentService).cancelAppointment(appointmentId, patientId);
    }

    @Test
    void cancelAppointment_nonPatientToken_shouldThrowWrongTokenExceptionAndNeverCallService() {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        when(jwt.getClaimAsString("role")).thenReturn("DOCTOR");

        // Act and Assert
        assertThatThrownBy(() -> appointmentController.cancelAppointment(appointmentId, jwt))
                .isInstanceOf(WrongTokenTypeException.class);

        verifyNoInteractions(appointmentService);
    }

    @Test
    void getMyAppointments_patientToken_shouldReturn200WithPatientIdFromTokenSubject() {
        // Arrange
        UUID patientId = UUID.randomUUID();
        when(jwt.getClaimAsString("role")).thenReturn("PATIENT");
        when(jwt.getSubject()).thenReturn(patientId.toString());

        List<AppointmentResponse> responses = List.of(
                AppointmentResponse.builder().id(UUID.randomUUID()).status(AppointmentStatus.CONFIRMED).build());
        when(appointmentService.getAppointmentsForPatient(patientId)).thenReturn(responses);

        // Act
        ResponseEntity<List<AppointmentResponse>> result = appointmentController.getMyAppointments(jwt);

        // Assert
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(responses);
        verify(appointmentService).getAppointmentsForPatient(patientId);
    }

    @Test
    void getMyAppointments_nonPatientToken_shouldThrowWrongTokenExceptionAndNeverCallService() {
        // Arrange
        when(jwt.getClaimAsString("role")).thenReturn("DOCTOR");

        // Act and Assert
        assertThatThrownBy(() -> appointmentController.getMyAppointments(jwt))
                .isInstanceOf(WrongTokenTypeException.class);

        verifyNoInteractions(appointmentService);
    }

    @Test
    void cancelAppointment_whenNotFound_shouldPropagateAppointmentNotFoundException() {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        when(jwt.getClaimAsString("role")).thenReturn("PATIENT");
        when(jwt.getSubject()).thenReturn(patientId.toString());
        when(appointmentService.cancelAppointment(appointmentId, patientId))
                .thenThrow(new AppointmentNotFoundException("Appointment not found: " + appointmentId));

        // Act and Assert: HTTP status mapping for this exception is GlobalExceptionHandler's
        // responsibility, not the controller's; here we assert the controller propagates it.
        assertThatThrownBy(() -> appointmentController.cancelAppointment(appointmentId, jwt))
                .isInstanceOf(AppointmentNotFoundException.class)
                .hasMessage("Appointment not found: " + appointmentId);
    }
}
