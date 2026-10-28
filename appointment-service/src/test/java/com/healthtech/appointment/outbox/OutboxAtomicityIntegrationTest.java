package com.healthtech.appointment.outbox;

import com.healthtech.appointment.controller.integration.AbstractAppointmentApiTest;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.dto.AppointmentUpdateRequest;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidPatient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// No Kafka container needed: the producer connects lazily and nothing sends here.
class OutboxAtomicityIntegrationTest extends AbstractAppointmentApiTest {

    private ResponseEntity<AppointmentResponse> bookAt(ValidPatient patient, ValidDoctor doctor, LocalDateTime dateTime) {
        return postBooking(patientHeaders(patient.getPatientId()), doctor.getDoctorId(), dateTime,
                AppointmentType.VACCINATION, null);
    }

    private ResponseEntity<String> moveTo(ValidPatient patient, UUID appointmentId, LocalDateTime dateTime) {
        return restTemplate.exchange("/api/appointments/" + appointmentId, HttpMethod.PATCH,
                new HttpEntity<>(AppointmentUpdateRequest.builder().dateTime(dateTime).build(),
                        patientHeaders(patient.getPatientId())),
                String.class);
    }

    @Test
    void bookAppointment_success_writesExactlyOneUnpublishedOutboxRow() {
        // Arrange
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor doctor = seedDoctor(target);
        ValidPatient patient = seedPatient();

        // Act
        ResponseEntity<AppointmentResponse> response = bookAt(patient, doctor, LocalDateTime.of(target, LocalTime.of(9, 0)));

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID appointmentId = response.getBody().getId();
        var rows = outboxRepository.findAll().stream()
                .filter(m -> m.getAggregateId().equals(appointmentId.toString()))
                .toList();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTopic()).isEqualTo("appointment.booked");
        assertThat(rows.get(0).getPublishedAt()).isNull();
    }

    @Test
    void bookAppointment_rollsBackOnSlotConflict_writesNoAdditionalOutboxRow() {
        // Arrange: the 10:00 slot is already booked by another patient
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor doctor = seedDoctor(target);
        ValidPatient firstPatient = seedPatient();
        ValidPatient secondPatient = seedPatient();
        LocalDateTime slot = LocalDateTime.of(target, LocalTime.of(10, 0));
        assertThat(bookAt(firstPatient, doctor, slot).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long countAfterFirst = outboxRepository.count();

        // Act
        ResponseEntity<String> secondResponse = restTemplate.exchange("/api/appointments", HttpMethod.POST,
                new HttpEntity<>(AppointmentRequest.builder().doctorId(doctor.getDoctorId()).dateTime(slot)
                        .type(AppointmentType.VACCINATION).build(), patientHeaders(secondPatient.getPatientId())),
                String.class);

        // Assert
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outboxRepository.count()).isEqualTo(countAfterFirst);
    }

    @Test
    void cancelAppointment_success_writesOutboxRowForCancelledEvent() {
        // Arrange: a booked appointment
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor doctor = seedDoctor(target);
        ValidPatient patient = seedPatient();
        ResponseEntity<AppointmentResponse> bookingResponse = bookAt(patient, doctor, LocalDateTime.of(target, LocalTime.of(11, 0)));
        assertThat(bookingResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID appointmentId = bookingResponse.getBody().getId();

        // Act
        ResponseEntity<String> cancelResponse = putCancel(patientHeaders(patient.getPatientId()), appointmentId);

        // Assert
        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        var cancelledRows = outboxRows(appointmentId, "appointment.cancelled");
        assertThat(cancelledRows).hasSize(1);
        assertThat(cancelledRows.get(0).getPublishedAt()).isNull();
    }

    @Test
    void changeAppointment_success_writesOneUnpublishedChangedRow() {
        // Arrange
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor doctor = seedDoctor(target);
        ValidPatient patient = seedPatient();
        UUID appointmentId = bookAt(patient, doctor, LocalDateTime.of(target, LocalTime.of(12, 0))).getBody().getId();

        // Act
        ResponseEntity<String> response = moveTo(patient, appointmentId, LocalDateTime.of(target, LocalTime.of(13, 0)));

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var changedRows = outboxRows(appointmentId, "appointment.changed");
        assertThat(changedRows).hasSize(1);
        assertThat(changedRows.get(0).getPublishedAt()).isNull();
    }

    @Test
    void changeAppointment_rollsBackOnSlotConflict_writesNoChangedRow() {
        // Arrange: 15:00 is already taken by another patient
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor doctor = seedDoctor(target);
        ValidPatient patient = seedPatient();
        UUID appointmentId = bookAt(patient, doctor, LocalDateTime.of(target, LocalTime.of(14, 0))).getBody().getId();
        bookAt(seedPatient(), doctor, LocalDateTime.of(target, LocalTime.of(15, 0)));

        // Act
        ResponseEntity<String> response = moveTo(patient, appointmentId, LocalDateTime.of(target, LocalTime.of(15, 0)));

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outboxRows(appointmentId, "appointment.changed")).isEmpty();
    }

    // -- the doctor's actions ----------------------------------------------------

    private ResponseEntity<String> doctorPut(UUID doctorId, UUID appointmentId, String action) {
        return restTemplate.exchange("/api/appointments/doctor/" + appointmentId + "/" + action, HttpMethod.PUT,
                new HttpEntity<>(doctorHeaders(doctorId)), String.class);
    }

    @Test
    void confirmAppointment_success_writesOneUnpublishedConfirmedRow() {
        // Arrange
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor doctor = seedDoctor(target);
        UUID appointmentId = bookAt(seedPatient(), doctor, LocalDateTime.of(target, LocalTime.of(9, 0))).getBody().getId();

        // Act
        ResponseEntity<String> response = doctorPut(doctor.getDoctorId(), appointmentId, "confirm");

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var rows = outboxRows(appointmentId, "appointment.confirmed");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPublishedAt()).isNull();
    }

    @Test
    void confirmAppointment_rejectedBecauseCancelled_writesNoConfirmedRow() {
        // Arrange
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor doctor = seedDoctor(target);
        UUID appointmentId = bookAt(seedPatient(), doctor, LocalDateTime.of(target, LocalTime.of(9, 30))).getBody().getId();
        doctorPut(doctor.getDoctorId(), appointmentId, "cancel");

        // Act
        ResponseEntity<String> response = doctorPut(doctor.getDoctorId(), appointmentId, "confirm");

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outboxRows(appointmentId, "appointment.confirmed")).isEmpty();
    }

    @Test
    void doctorCancel_success_writesOneUnpublishedCancelledRow() {
        // Arrange
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor doctor = seedDoctor(target);
        UUID appointmentId = bookAt(seedPatient(), doctor, LocalDateTime.of(target, LocalTime.of(10, 0))).getBody().getId();

        // Act
        ResponseEntity<String> response = doctorPut(doctor.getDoctorId(), appointmentId, "cancel");

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var rows = outboxRows(appointmentId, "appointment.cancelled");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPublishedAt()).isNull();
    }

    @Test
    void doctorCancel_ofAnotherDoctorsAppointment_writesNoRow() {
        // Arrange
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor doctor = seedDoctor(target);
        ValidDoctor other = seedDoctor(target);
        UUID appointmentId = bookAt(seedPatient(), doctor, LocalDateTime.of(target, LocalTime.of(10, 30))).getBody().getId();

        // Act
        ResponseEntity<String> response = doctorPut(other.getDoctorId(), appointmentId, "cancel");

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(outboxRows(appointmentId, "appointment.cancelled")).isEmpty();
    }

    @Test
    void doctorChange_success_writesOneChangedRowAndOnSlotConflictNone() {
        // Arrange
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor doctor = seedDoctor(target);
        ValidPatient patient = seedPatient();
        UUID moved = bookAt(patient, doctor, LocalDateTime.of(target, LocalTime.of(11, 0))).getBody().getId();
        UUID blocked = bookAt(patient, doctor, LocalDateTime.of(target, LocalTime.of(12, 0))).getBody().getId();

        // Act
        ResponseEntity<String> ok = restTemplate.exchange("/api/appointments/doctor/" + moved, HttpMethod.PATCH,
                new HttpEntity<>(com.healthtech.appointment.dto.DoctorAppointmentUpdateRequest.builder()
                        .dateTime(LocalDateTime.of(target, LocalTime.of(13, 0))).build(), doctorHeaders(doctor.getDoctorId())),
                String.class);
        ResponseEntity<String> conflict = restTemplate.exchange("/api/appointments/doctor/" + blocked, HttpMethod.PATCH,
                new HttpEntity<>(com.healthtech.appointment.dto.DoctorAppointmentUpdateRequest.builder()
                        .dateTime(LocalDateTime.of(target, LocalTime.of(13, 0))).build(), doctorHeaders(doctor.getDoctorId())),
                String.class);

        // Assert
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outboxRows(moved, "appointment.changed")).hasSize(1);
        assertThat(outboxRows(blocked, "appointment.changed")).isEmpty();
    }
}
