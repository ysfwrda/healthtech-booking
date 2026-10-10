package com.healthtech.appointment.controller.integration;

import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.outbox.OutboxMessage;
import com.healthtech.appointment.outbox.OutboxRepository;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.readmodel.ValidPatient;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
import com.healthtech.appointment.repository.AppointmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Shared setup for the full-stack appointment API tests: one Postgres container and one key pair for every
// subclass, so Spring reuses a single application context, plus helpers to seed the read model and call the API.
// The relay delay is pushed out to an hour: these tests don't assert on Kafka delivery, so the live relay would
// only retry against an unreachable broker and 'unpublished' outbox assertions stay deterministic.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "outbox.relay.fixed-delay-ms=3600000")
@Import(AbstractAppointmentApiTest.TestSecurityConfig.class)
public abstract class AbstractAppointmentApiTest {

    // generated once, in a static initializer, so it exists before the context builds
    protected static final KeyPair KEY_PAIR;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KEY_PAIR = gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    protected ValidPatientRepository validPatientRepository;
    @Autowired
    protected ValidDoctorRepository validDoctorRepository;
    @Autowired
    protected AppointmentRepository appointmentRepository;
    @Autowired
    protected OutboxRepository outboxRepository;
    @Autowired
    protected TestRestTemplate restTemplate;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected PlatformTransactionManager transactionManager;

    // A doctor open 09:00-17:00 on the weekday of the given date.
    protected ValidDoctor seedDoctor(LocalDate openOn) {
        return validDoctorRepository.save(ValidDoctor.builder()
                .doctorId(UUID.randomUUID())
                .firstName("Valid")
                .lastName("Doctor")
                .openingHours(Set.of(OpeningHours.builder()
                        .dayOfWeek(openOn.getDayOfWeek())
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build()))
                .build());
    }

    protected ValidPatient seedPatient() {
        return validPatientRepository.save(ValidPatient.builder()
                .patientId(UUID.randomUUID())
                .firstName("Valid")
                .lastName("Patient")
                .email("valid.patient@example.com")
                .build());
    }

    // JSON request headers carrying a PATIENT token signed with the trusted key pair.
    protected static HttpHeaders patientHeaders(UUID patientId) {
        return bearerHeaders(TestJwtFactory.patientToken(patientId, (RSAPrivateKey) KEY_PAIR.getPrivate()));
    }

    protected static HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    protected ResponseEntity<AppointmentResponse> postBooking(HttpHeaders headers, UUID doctorId,
                                                              LocalDateTime dateTime, AppointmentType type, String notes) {
        return restTemplate.exchange("/api/appointments", HttpMethod.POST,
                new HttpEntity<>(AppointmentRequest.builder().doctorId(doctorId).dateTime(dateTime)
                        .type(type).notes(notes).build(), headers),
                AppointmentResponse.class);
    }

    protected ResponseEntity<String> putCancel(HttpHeaders headers, UUID appointmentId) {
        return restTemplate.exchange("/api/appointments/" + appointmentId + "/cancel", HttpMethod.PUT,
                new HttpEntity<>(headers), String.class);
    }

    // Books through the API and returns the new appointment's id.
    protected UUID book(ValidPatient patient, ValidDoctor doctor, LocalDateTime dateTime, AppointmentType type) {
        ResponseEntity<AppointmentResponse> response = postBooking(patientHeaders(patient.getPatientId()),
                doctor.getDoctorId(), dateTime, type, "original notes");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return response.getBody().getId();
    }

    protected List<OutboxMessage> outboxRows(UUID appointmentId, String topic) {
        return outboxRepository.findAll().stream()
                .filter(m -> m.getAggregateId().equals(appointmentId.toString()) && m.getTopic().equals(topic))
                .toList();
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
