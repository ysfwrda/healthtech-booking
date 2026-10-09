package com.healthtech.appointment.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.dto.AppointmentUpdateRequest;
import com.healthtech.appointment.outbox.OutboxMessage;
import com.healthtech.appointment.outbox.OutboxRepository;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.readmodel.ValidPatient;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
import com.healthtech.appointment.repository.AppointmentRepository;
import org.junit.jupiter.api.Test;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// PATCH /api/appointments/{id} end to end against real Postgres: the unique slot index, the row lock and the outbox.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "outbox.relay.fixed-delay-ms=3600000")
@Import(AppointmentChangeIntegrationTest.TestSecurityConfig.class)
class AppointmentChangeIntegrationTest {

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

    // A week out, so every appointment here is far outside the 48 hour notice period.
    private static final LocalDate NEXT_WEEK = LocalDate.now().plusWeeks(1);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    ValidPatientRepository validPatientRepository;
    @Autowired
    ValidDoctorRepository validDoctorRepository;
    @Autowired
    AppointmentRepository appointmentRepository;
    @Autowired
    TestRestTemplate restTemplate;
    @Autowired
    OutboxRepository outboxRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    PlatformTransactionManager transactionManager;

    private record Patient(UUID id, HttpHeaders headers) {
    }

    private ValidDoctor seedDoctor() {
        return validDoctorRepository.save(ValidDoctor.builder()
                .doctorId(UUID.randomUUID())
                .firstName("Valid")
                .lastName("Doctor")
                .openingHours(Set.of(OpeningHours.builder()
                        .dayOfWeek(NEXT_WEEK.getDayOfWeek())
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0)).build()))
                .build());
    }

    private Patient seedPatient() {
        ValidPatient patient = validPatientRepository.save(ValidPatient.builder()
                .patientId(UUID.randomUUID())
                .firstName("Valid")
                .lastName("Patient")
                .build());
        return new Patient(patient.getPatientId(), headersFor(patient.getPatientId()));
    }

    private static HttpHeaders headersFor(UUID patientId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtFactory.patientToken(patientId, (RSAPrivateKey) KEY_PAIR.getPrivate()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static LocalDateTime slot(int hour, int minute) {
        return LocalDateTime.of(NEXT_WEEK, LocalTime.of(hour, minute));
    }

    private UUID book(Patient patient, ValidDoctor doctor, LocalDateTime dateTime) {
        ResponseEntity<AppointmentResponse> response = restTemplate.exchange("/api/appointments", HttpMethod.POST,
                new HttpEntity<>(AppointmentRequest.builder().doctorId(doctor.getDoctorId()).dateTime(dateTime)
                        .type(AppointmentType.INITIAL_CONSULTATION).notes("original notes").build(), patient.headers()),
                AppointmentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().getId();
    }

    private ResponseEntity<String> patchAs(HttpHeaders headers, UUID appointmentId, AppointmentUpdateRequest body) {
        return restTemplate.exchange("/api/appointments/" + appointmentId, HttpMethod.PATCH,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> cancelAs(HttpHeaders headers, UUID appointmentId) {
        return restTemplate.exchange("/api/appointments/" + appointmentId + "/cancel", HttpMethod.PUT,
                new HttpEntity<>(headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private Appointment stored(UUID appointmentId) {
        return appointmentRepository.findById(appointmentId).orElseThrow();
    }

    private List<OutboxMessage> changedEvents(UUID appointmentId) {
        return outboxRepository.findAll().stream()
                .filter(m -> m.getAggregateId().equals(appointmentId.toString()) && m.getTopic().equals("appointment.changed"))
                .toList();
    }

    // --- happy paths ---

    @Test
    void changeAppointment_newFreeSlot_movesItFreesTheOldSlotAndPublishesEvent() throws Exception {
        // Arrange
        ValidDoctor doctor = seedDoctor();
        Patient patient = seedPatient();
        UUID id = book(patient, doctor, slot(9, 0));

        // Act
        ResponseEntity<String> response = patchAs(patient.headers(), id,
                AppointmentUpdateRequest.builder().dateTime(slot(10, 30)).build());

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(LocalDateTime.parse(json(response).get("dateTime").asText())).isEqualTo(slot(10, 30));
        assertThat(stored(id).getDateTime()).isEqualTo(slot(10, 30));
        List<OutboxMessage> events = changedEvents(id);
        assertThat(events).hasSize(1);
        JsonNode payload = objectMapper.readTree(events.get(0).getPayload());
        assertThat(payload.get("patientId").asText()).isEqualTo(patient.id().toString());
        assertThat(LocalDateTime.parse(payload.get("dateTime").asText())).isEqualTo(slot(10, 30));
        assertThat(LocalDateTime.parse(payload.get("previousDateTime").asText())).isEqualTo(slot(9, 0));
        // the vacated slot can be booked by someone else
        book(seedPatient(), doctor, slot(9, 0));
    }

    @Test
    void changeAppointment_typeAndNotes_updatesBothAndPublishesEventWithPreviousType() throws Exception {
        // Arrange
        ValidDoctor doctor = seedDoctor();
        Patient patient = seedPatient();
        UUID id = book(patient, doctor, slot(9, 0));

        // Act
        ResponseEntity<String> response = patchAs(patient.headers(), id,
                AppointmentUpdateRequest.builder().type(AppointmentType.FOLLOW_UP).notes("new notes").build());

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Appointment appointment = stored(id);
        assertThat(appointment.getType()).isEqualTo(AppointmentType.FOLLOW_UP);
        assertThat(appointment.getNotes()).isEqualTo("new notes");
        assertThat(appointment.getDateTime()).isEqualTo(slot(9, 0));
        JsonNode payload = objectMapper.readTree(changedEvents(id).get(0).getPayload());
        assertThat(payload.get("type").asText()).isEqualTo("FOLLOW_UP");
        assertThat(payload.get("previousType").asText()).isEqualTo("INITIAL_CONSULTATION");
    }

    @Test
    void changeAppointment_notesOnly_publishesNoEvent() {
        // Arrange
        ValidDoctor doctor = seedDoctor();
        Patient patient = seedPatient();
        UUID id = book(patient, doctor, slot(9, 0));

        // Act
        ResponseEntity<String> response = patchAs(patient.headers(), id,
                AppointmentUpdateRequest.builder().notes("only notes").build());

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stored(id).getNotes()).isEqualTo("only notes");
        assertThat(changedEvents(id)).isEmpty();
    }

    // --- rejections that must leave the appointment as it was ---

    @Test
    void changeAppointment_slotTakenByAnotherPatient_returns409AndKeepsTheOldTime() throws Exception {
        // Arrange
        ValidDoctor doctor = seedDoctor();
        Patient patient = seedPatient();
        UUID id = book(patient, doctor, slot(9, 0));
        book(seedPatient(), doctor, slot(10, 0));

        // Act
        ResponseEntity<String> response = patchAs(patient.headers(), id,
                AppointmentUpdateRequest.builder().dateTime(slot(10, 0)).build());

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).get("title").asText()).isEqualTo("Slot Already Booked");
        assertThat(stored(id).getDateTime()).isEqualTo(slot(9, 0));
        assertThat(changedEvents(id)).isEmpty();
    }

    @Test
    void changeAppointment_outsideOpeningHours_returns400() throws Exception {
        // Arrange
        ValidDoctor doctor = seedDoctor();
        Patient patient = seedPatient();
        UUID id = book(patient, doctor, slot(9, 0));

        // Act
        ResponseEntity<String> response = patchAs(patient.headers(), id,
                AppointmentUpdateRequest.builder().dateTime(slot(17, 0)).build());

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("title").asText()).isEqualTo("Outside Opening Hours");
        assertThat(stored(id).getDateTime()).isEqualTo(slot(9, 0));
    }

    @Test
    void changeAppointment_emptyBody_returns400() throws Exception {
        // Arrange
        Patient patient = seedPatient();
        UUID id = book(patient, seedDoctor(), slot(9, 0));

        // Act
        ResponseEntity<String> response = patchAs(patient.headers(), id, new AppointmentUpdateRequest());

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("title").asText()).isEqualTo("Validation Error");
    }

    @Test
    void changeAppointment_startsInLessThan48Hours_returns409ChangeWindowClosed() throws Exception {
        // Arrange
        Patient patient = seedPatient();
        Appointment soon = appointmentRepository.save(Appointment.builder()
                .patientId(patient.id())
                .doctorId(UUID.randomUUID())
                .dateTime(LocalDateTime.now().plusHours(47))
                .duration(30)
                .type(AppointmentType.INITIAL_CONSULTATION)
                .status(AppointmentStatus.CONFIRMED)
                .build());

        // Act
        ResponseEntity<String> response = patchAs(patient.headers(), soon.getId(),
                AppointmentUpdateRequest.builder().type(AppointmentType.FOLLOW_UP).build());

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).get("title").asText()).isEqualTo("Change Window Closed");
        assertThat(stored(soon.getId()).getType()).isEqualTo(AppointmentType.INITIAL_CONSULTATION);
    }

    @Test
    void changeAppointment_cancelledAppointment_returns409NotChangeable() throws Exception {
        // Arrange
        Patient patient = seedPatient();
        UUID id = book(patient, seedDoctor(), slot(9, 0));
        assertThat(cancelAs(patient.headers(), id).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Act
        ResponseEntity<String> response = patchAs(patient.headers(), id,
                AppointmentUpdateRequest.builder().type(AppointmentType.FOLLOW_UP).build());

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).get("title").asText()).isEqualTo("Appointment Not Changeable");
        assertThat(stored(id).getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
    }

    // --- who may change ---

    @Test
    void changeAppointment_notOwner_returns403AndChangesNothing() throws Exception {
        // Arrange
        UUID id = book(seedPatient(), seedDoctor(), slot(9, 0));
        Patient stranger = seedPatient();

        // Act
        ResponseEntity<String> response = patchAs(stranger.headers(), id,
                AppointmentUpdateRequest.builder().type(AppointmentType.FOLLOW_UP).build());

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(response).get("title").asText()).isEqualTo("Not Resource Owner");
        assertThat(stored(id).getType()).isEqualTo(AppointmentType.INITIAL_CONSULTATION);
    }

    @Test
    void changeAppointment_unknownId_returns404() throws Exception {
        // Arrange
        Patient patient = seedPatient();

        // Act
        ResponseEntity<String> response = patchAs(patient.headers(), UUID.randomUUID(),
                AppointmentUpdateRequest.builder().type(AppointmentType.FOLLOW_UP).build());

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(response).get("title").asText()).isEqualTo("Appointment Not Found");
    }

    @Test
    void changeAppointment_noToken_returns401() {
        // Act
        ResponseEntity<String> response = restTemplate.exchange("/api/appointments/" + UUID.randomUUID(),
                HttpMethod.PATCH,
                new HttpEntity<>(AppointmentUpdateRequest.builder().type(AppointmentType.FOLLOW_UP).build(), jsonHeaders()),
                String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void changeAppointment_doctorToken_returns403() {
        // Arrange
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(TestJwtFactory.doctorToken(UUID.randomUUID(), (RSAPrivateKey) KEY_PAIR.getPrivate()));

        // Act
        ResponseEntity<String> response = patchAs(headers, UUID.randomUUID(),
                AppointmentUpdateRequest.builder().type(AppointmentType.FOLLOW_UP).build());

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // --- concurrency ---

    @Test
    void changeAppointment_concurrentMovesToTheSameSlot_oneSucceedsTheRestGet409() throws Exception {
        // Arrange: five patients, each with an appointment of their own, all want 16:00
        ValidDoctor doctor = seedDoctor();
        int movers = 5;
        List<Callable<HttpStatusCode>> moves = new ArrayList<>();
        for (int i = 0; i < movers; i++) {
            Patient patient = seedPatient();
            UUID id = book(patient, doctor, slot(9 + i, 0));
            moves.add(() -> patchAs(patient.headers(), id,
                    AppointmentUpdateRequest.builder().dateTime(slot(16, 0)).build()).getStatusCode());
        }

        // Act
        List<HttpStatusCode> statuses = runTogether(moves);

        // Assert
        assertThat(statuses.stream().filter(s -> s.equals(HttpStatus.OK)).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s.equals(HttpStatus.CONFLICT)).count()).isEqualTo(movers - 1);
    }

    @Test
    void changeAppointment_racingACancel_neverRevivesTheCancelledAppointment() throws Exception {
        // Arrange and Act: repeated so both orders of the race are likely to occur
        ValidDoctor doctor = seedDoctor();
        for (int round = 0; round < 6; round++) {
            Patient patient = seedPatient();
            UUID id = book(patient, doctor, slot(9 + round, 0));
            List<Callable<HttpStatusCode>> race = List.of(
                    () -> cancelAs(patient.headers(), id).getStatusCode(),
                    () -> patchAs(patient.headers(), id,
                            AppointmentUpdateRequest.builder().type(AppointmentType.FOLLOW_UP).build()).getStatusCode());

            List<HttpStatusCode> statuses = runTogether(race);

            // Assert
            assertThat(statuses.get(0)).isEqualTo(HttpStatus.OK);
            assertThat(statuses.get(1)).isIn(HttpStatus.OK, HttpStatus.CONFLICT);
            assertThat(stored(id).getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        }
    }

    @Test
    void changeAppointment_rowHeldByAnotherTransaction_returns503ThenSucceedsOnceReleased() throws Exception {
        // Arrange
        Patient patient = seedPatient();
        UUID id = book(patient, seedDoctor(), slot(9, 0));
        AppointmentUpdateRequest request = AppointmentUpdateRequest.builder().type(AppointmentType.FOLLOW_UP).build();
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService holderExecutor = Executors.newSingleThreadExecutor()) {
            Future<?> holder = holderExecutor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbcTemplate.queryForList("select id from appointments where id = ? for update", id);
                        rowLocked.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();

            // Act
            ResponseEntity<String> blocked = patchAs(patient.headers(), id, request);

            // Assert
            assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(json(blocked).get("title").asText()).isEqualTo("Appointment Busy");
            assertThat(stored(id).getType()).isEqualTo(AppointmentType.INITIAL_CONSULTATION);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(patchAs(patient.headers(), id, request).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stored(id).getType()).isEqualTo(AppointmentType.FOLLOW_UP);
    }

    // Starts all calls at the same moment and returns their results in the order given.
    private static List<HttpStatusCode> runTogether(List<Callable<HttpStatusCode>> calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(calls.size())) {
            List<Future<HttpStatusCode>> futures = new ArrayList<>();
            for (Callable<HttpStatusCode> call : calls) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<HttpStatusCode> results = new ArrayList<>();
            for (Future<HttpStatusCode> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        }
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
