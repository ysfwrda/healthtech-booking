package com.healthtech.appointment.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.event.AppointmentBooked;
import com.healthtech.appointment.readmodel.*;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AppointmentIntegrationTest.TestSecurityConfig.class)
public class AppointmentIntegrationTest {
    // generated once, in a static initializer, so it exists before the context builds
    static final KeyPair KEY_PAIR;
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

    @MockitoBean
    KafkaTemplate<String, AppointmentBooked> kafkaTemplate;
    @Autowired
    ValidPatientRepository validPatientRepository;
    @Autowired
    ValidDoctorRepository validDoctorRepository;
    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void createAppointment_concurrentUsers_returnStatus409() throws Exception {
        LocalDate target = LocalDate.now().plusWeeks(1);
        Set<OpeningHours> openingHours = new HashSet<OpeningHours>();
        openingHours.add(OpeningHours.builder()
                .dayOfWeek(target.getDayOfWeek())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0)).build());

        final ValidDoctor seededDoctor = ValidDoctor.builder()
                .doctorId(UUID.randomUUID())
                .firstName("Valid")
                .lastName("Doctor")
                .openingHours(openingHours)
                .build();

        validDoctorRepository.save(seededDoctor);

        int numberOfUsers = 10;
        ConcurrentLinkedQueue<HttpStatusCode> statusPool = new ConcurrentLinkedQueue<>();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        try (ExecutorService executorService = Executors.newFixedThreadPool(numberOfUsers)) {
            for (int i = 0; i < numberOfUsers; i++) {
                executorService.execute(() -> {
                    try {
                        countDownLatch.await();
                        ValidPatient seededPatient = ValidPatient.builder()
                                .patientId(UUID.randomUUID())
                                .firstName("Valid")
                                .lastName("Patient ")
                                .build();
                        seededPatient = validPatientRepository.save(seededPatient);
                        String token = TestJwtFactory.patientToken(
                                seededPatient.getPatientId(), (RSAPrivateKey) KEY_PAIR.getPrivate());
                        HttpHeaders headers = new HttpHeaders();
                        headers.setBearerAuth(token);
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        AppointmentRequest appointmentRequest = AppointmentRequest.builder()
                                .doctorId(seededDoctor.getDoctorId())
                                .dateTime(LocalDateTime.of(target, LocalTime.of(9, 0)))
                                .notes("Test Notes")
                                .type(AppointmentType.VACCINATION)
                                .build();

                        ResponseEntity<String> response = restTemplate.exchange(
                                "/api/appointments", HttpMethod.POST,
                                new HttpEntity<>(appointmentRequest, headers),
                                String.class);
                        statusPool.add(response.getStatusCode());
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            countDownLatch.countDown();
        }
        assertThat(statusPool.size()).isEqualTo(numberOfUsers);
        Long numberSucceeded = statusPool.stream().filter(s -> s.equals(HttpStatus.CREATED)).count();
        Long numberFailed = statusPool.stream().filter(s -> s.equals(HttpStatus.CONFLICT)).count();
        assertThat(numberSucceeded).isEqualTo(1);
        assertThat(numberFailed).isEqualTo(numberOfUsers - 1);
    }

    @Test
    public void createAppointment_signWithUntrustedKeypair_returns401() throws NoSuchAlgorithmException {
        LocalDate target = LocalDate.now().plusWeeks(1);
        Set<OpeningHours> openingHours = new HashSet<OpeningHours>();
        openingHours.add(OpeningHours.builder()
                .dayOfWeek(target.getDayOfWeek())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0)).build());

        final ValidDoctor seededDoctor = ValidDoctor.builder()
                .doctorId(UUID.randomUUID())
                .firstName("Valid")
                .lastName("Doctor")
                .openingHours(openingHours)
                .build();
        validDoctorRepository.save(seededDoctor);

        ValidPatient seededPatient = ValidPatient.builder()
                .patientId(UUID.randomUUID())
                .firstName("Valid")
                .lastName("Patient ")
                .build();
        seededPatient = validPatientRepository.save(seededPatient);
        
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        RSAPrivateKey untrustedPrivateKey = (RSAPrivateKey) gen.generateKeyPair().getPrivate();
        String token = TestJwtFactory.patientToken(
                seededPatient.getPatientId(), untrustedPrivateKey);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        AppointmentRequest appointmentRequest = AppointmentRequest.builder()
                .doctorId(seededDoctor.getDoctorId())
                .dateTime(LocalDateTime.of(target, LocalTime.of(9, 0)))
                .notes("Test Notes")
                .type(AppointmentType.VACCINATION)
                .build();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/appointments", HttpMethod.POST,
                new HttpEntity<>(appointmentRequest, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void cancelAppointment_notOwner_returns403() throws Exception {
        LocalDate target = LocalDate.now().plusWeeks(1);
        Set<OpeningHours> openingHours = new HashSet<OpeningHours>();
        openingHours.add(OpeningHours.builder()
                .dayOfWeek(target.getDayOfWeek())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0)).build());

        final ValidDoctor seededDoctor = ValidDoctor.builder()
                .doctorId(UUID.randomUUID())
                .firstName("Valid")
                .lastName("Doctor")
                .openingHours(openingHours)
                .build();
        validDoctorRepository.save(seededDoctor);

        ValidPatient patientB = ValidPatient.builder()
                .patientId(UUID.randomUUID())
                .firstName("Patient")
                .lastName("B")
                .build();
        patientB = validPatientRepository.save(patientB);

        String tokenB = TestJwtFactory.patientToken(patientB.getPatientId(), (RSAPrivateKey) KEY_PAIR.getPrivate());
        HttpHeaders headersB = new HttpHeaders();
        headersB.setBearerAuth(tokenB);
        headersB.setContentType(MediaType.APPLICATION_JSON);

        AppointmentRequest bookingRequest = AppointmentRequest.builder()
                .doctorId(seededDoctor.getDoctorId())
                .dateTime(LocalDateTime.of(target, LocalTime.of(9, 0)))
                .notes("Patient B's appointment")
                .type(AppointmentType.VACCINATION)
                .build();

        ResponseEntity<AppointmentResponse> bookingResponse = restTemplate.exchange(
                "/api/appointments", HttpMethod.POST,
                new HttpEntity<>(bookingRequest, headersB),
                AppointmentResponse.class);
        assertThat(bookingResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID appointmentId = bookingResponse.getBody().getId();

        ValidPatient patientA = ValidPatient.builder()
                .patientId(UUID.randomUUID())
                .firstName("Patient")
                .lastName("A")
                .build();
        patientA = validPatientRepository.save(patientA);
        String tokenA = TestJwtFactory.patientToken(patientA.getPatientId(), (RSAPrivateKey) KEY_PAIR.getPrivate());
        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(tokenA);

        ResponseEntity<String> cancelResponse = restTemplate.exchange(
                "/api/appointments/" + appointmentId + "/cancel", HttpMethod.PUT,
                new HttpEntity<>(headersA), String.class);

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode problem = new ObjectMapper().readTree(cancelResponse.getBody());
        assertThat(problem.get("status").asInt()).isEqualTo(403);
        assertThat(problem.get("title").asText()).isEqualTo("Not Resource Owner");
        assertThat(problem.get("detail").asText()).contains(appointmentId.toString());
    }

    @Test
    void cancelAppointment_nonexistentId_returns404() throws Exception {
        ValidPatient seededPatient = ValidPatient.builder()
                .patientId(UUID.randomUUID())
                .firstName("Valid")
                .lastName("Patient")
                .build();
        seededPatient = validPatientRepository.save(seededPatient);
        String token = TestJwtFactory.patientToken(seededPatient.getPatientId(), (RSAPrivateKey) KEY_PAIR.getPrivate());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        UUID nonexistentId = UUID.randomUUID();
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/appointments/" + nonexistentId + "/cancel", HttpMethod.PUT,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode problem = new ObjectMapper().readTree(response.getBody());
        assertThat(problem.get("status").asInt()).isEqualTo(404);
        assertThat(problem.get("title").asText()).isEqualTo("Appointment Not Found");
        assertThat(problem.get("detail").asText()).contains(nonexistentId.toString());
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
