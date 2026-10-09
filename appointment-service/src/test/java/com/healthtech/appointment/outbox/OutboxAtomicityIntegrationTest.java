package com.healthtech.appointment.outbox;

import com.healthtech.appointment.controller.integration.TestJwtFactory;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.dto.AppointmentUpdateRequest;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.readmodel.ValidPatient;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Relay delay pushed out to an hour so it never fires, keeping 'unpublished' assertions deterministic.
// No Kafka container needed: the producer connects lazily and nothing sends here.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "outbox.relay.fixed-delay-ms=3600000")
@Import(OutboxAtomicityIntegrationTest.TestSecurityConfig.class)
class OutboxAtomicityIntegrationTest {

    static final KeyPair KEY_PAIR;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KEY_PAIR = gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Autowired
    ValidPatientRepository validPatientRepository;
    @Autowired
    ValidDoctorRepository validDoctorRepository;
    @Autowired
    OutboxRepository outboxRepository;
    @Autowired
    TestRestTemplate restTemplate;

    private HttpHeaders authHeaders(UUID patientId) {
        String token = TestJwtFactory.patientToken(patientId, (RSAPrivateKey) KEY_PAIR.getPrivate());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ValidDoctor seedDoctor(LocalDate date) {
        ValidDoctor doctor = ValidDoctor.builder()
                .doctorId(UUID.randomUUID())
                .firstName("Valid")
                .lastName("Doctor")
                .openingHours(Set.of(OpeningHours.builder()
                        .dayOfWeek(date.getDayOfWeek())
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build()))
                .build();
        return validDoctorRepository.save(doctor);
    }

    private ValidPatient seedPatient() {
        return validPatientRepository.save(ValidPatient.builder()
                .patientId(UUID.randomUUID())
                .firstName("Valid")
                .lastName("Patient")
                .email("valid.patient@example.com")
                .build());
    }

    @Test
    void bookAppointment_success_writesExactlyOneUnpublishedOutboxRow() {
        // Arrange
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor doctor = seedDoctor(target);
        ValidPatient patient = seedPatient();
        AppointmentRequest request = AppointmentRequest.builder()
                .doctorId(doctor.getDoctorId())
                .dateTime(LocalDateTime.of(target, LocalTime.of(9, 0)))
                .type(AppointmentType.VACCINATION)
                .build();

        // Act
        ResponseEntity<AppointmentResponse> response = restTemplate.exchange(
                "/api/appointments", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(patient.getPatientId())),
                AppointmentResponse.class);

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
        AppointmentRequest request = AppointmentRequest.builder()
                .doctorId(doctor.getDoctorId())
                .dateTime(LocalDateTime.of(target, LocalTime.of(10, 0)))
                .type(AppointmentType.VACCINATION)
                .build();
        ResponseEntity<AppointmentResponse> firstResponse = restTemplate.exchange(
                "/api/appointments", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(firstPatient.getPatientId())),
                AppointmentResponse.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long countAfterFirst = outboxRepository.count();

        // Act
        ResponseEntity<String> secondResponse = restTemplate.exchange(
                "/api/appointments", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(secondPatient.getPatientId())),
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
        AppointmentRequest request = AppointmentRequest.builder()
                .doctorId(doctor.getDoctorId())
                .dateTime(LocalDateTime.of(target, LocalTime.of(11, 0)))
                .type(AppointmentType.VACCINATION)
                .build();
        ResponseEntity<AppointmentResponse> bookingResponse = restTemplate.exchange(
                "/api/appointments", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(patient.getPatientId())),
                AppointmentResponse.class);
        assertThat(bookingResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID appointmentId = bookingResponse.getBody().getId();

        // Act
        ResponseEntity<AppointmentResponse> cancelResponse = restTemplate.exchange(
                "/api/appointments/" + appointmentId + "/cancel", HttpMethod.PUT,
                new HttpEntity<>(authHeaders(patient.getPatientId())),
                AppointmentResponse.class);

        // Assert
        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        var cancelledRows = outboxRepository.findAll().stream()
                .filter(m -> m.getAggregateId().equals(appointmentId.toString())
                        && m.getTopic().equals("appointment.cancelled"))
                .toList();
        assertThat(cancelledRows).hasSize(1);
        assertThat(cancelledRows.get(0).getPublishedAt()).isNull();
    }

    private UUID bookAt(ValidPatient patient, ValidDoctor doctor, LocalDateTime dateTime) {
        ResponseEntity<AppointmentResponse> response = restTemplate.exchange(
                "/api/appointments", HttpMethod.POST,
                new HttpEntity<>(AppointmentRequest.builder().doctorId(doctor.getDoctorId()).dateTime(dateTime)
                        .type(AppointmentType.VACCINATION).build(), authHeaders(patient.getPatientId())),
                AppointmentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().getId();
    }

    private ResponseEntity<String> moveTo(ValidPatient patient, UUID appointmentId, LocalDateTime dateTime) {
        return restTemplate.exchange("/api/appointments/" + appointmentId, HttpMethod.PATCH,
                new HttpEntity<>(AppointmentUpdateRequest.builder().dateTime(dateTime).build(), authHeaders(patient.getPatientId())),
                String.class);
    }

    private long changedRowCount(UUID appointmentId) {
        return outboxRepository.findAll().stream()
                .filter(m -> m.getAggregateId().equals(appointmentId.toString()) && m.getTopic().equals("appointment.changed"))
                .count();
    }

    @Test
    void changeAppointment_success_writesOneUnpublishedChangedRow() {
        // Arrange
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor doctor = seedDoctor(target);
        ValidPatient patient = seedPatient();
        UUID appointmentId = bookAt(patient, doctor, LocalDateTime.of(target, LocalTime.of(12, 0)));

        // Act
        ResponseEntity<String> response = moveTo(patient, appointmentId, LocalDateTime.of(target, LocalTime.of(13, 0)));

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var changedRows = outboxRepository.findAll().stream()
                .filter(m -> m.getAggregateId().equals(appointmentId.toString()) && m.getTopic().equals("appointment.changed"))
                .toList();
        assertThat(changedRows).hasSize(1);
        assertThat(changedRows.get(0).getPublishedAt()).isNull();
    }

    @Test
    void changeAppointment_rollsBackOnSlotConflict_writesNoChangedRow() {
        // Arrange: 15:00 is already taken by another patient
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor doctor = seedDoctor(target);
        ValidPatient patient = seedPatient();
        UUID appointmentId = bookAt(patient, doctor, LocalDateTime.of(target, LocalTime.of(14, 0)));
        bookAt(seedPatient(), doctor, LocalDateTime.of(target, LocalTime.of(15, 0)));

        // Act
        ResponseEntity<String> response = moveTo(patient, appointmentId, LocalDateTime.of(target, LocalTime.of(15, 0)));

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(changedRowCount(appointmentId)).isZero();
    }

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return NimbusJwtDecoder
                    .withPublicKey((RSAPublicKey) KEY_PAIR.getPublic())
                    .build();
        }
    }
}
