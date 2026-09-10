package com.healthtech.appointment.outbox;

import com.healthtech.appointment.controller.integration.TestJwtFactory;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
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

// The relay's fixed delay is pushed out to an hour so it never fires during this test class,
// keeping "still unpublished" assertions deterministic instead of racing a live scheduler. No
// Kafka container is needed: ProducerFactory bean creation is lazy and never actually connects
// unless something sends, which nothing does here.
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
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor doctor = seedDoctor(target);
        ValidPatient patient = seedPatient();

        AppointmentRequest request = AppointmentRequest.builder()
                .doctorId(doctor.getDoctorId())
                .dateTime(LocalDateTime.of(target, LocalTime.of(9, 0)))
                .type(AppointmentType.VACCINATION)
                .build();

        ResponseEntity<AppointmentResponse> response = restTemplate.exchange(
                "/api/appointments", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(patient.getPatientId())),
                AppointmentResponse.class);
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

        ResponseEntity<String> secondResponse = restTemplate.exchange(
                "/api/appointments", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(secondPatient.getPatientId())),
                String.class);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(outboxRepository.count()).isEqualTo(countAfterFirst);
    }

    @Test
    void cancelAppointment_success_writesOutboxRowForCancelledEvent() {
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

        ResponseEntity<AppointmentResponse> cancelResponse = restTemplate.exchange(
                "/api/appointments/" + appointmentId + "/cancel", HttpMethod.PUT,
                new HttpEntity<>(authHeaders(patient.getPatientId())),
                AppointmentResponse.class);
        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        var cancelledRows = outboxRepository.findAll().stream()
                .filter(m -> m.getAggregateId().equals(appointmentId.toString())
                        && m.getTopic().equals("appointment.cancelled"))
                .toList();
        assertThat(cancelledRows).hasSize(1);
        assertThat(cancelledRows.get(0).getPublishedAt()).isNull();
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
