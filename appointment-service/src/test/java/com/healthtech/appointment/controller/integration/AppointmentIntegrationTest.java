package com.healthtech.appointment.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidPatient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class AppointmentIntegrationTest extends AbstractAppointmentApiTest {

    @Test
    void createAppointment_concurrentUsers_returnStatus409() throws Exception {
        LocalDate target = LocalDate.now().plusWeeks(1);
        final ValidDoctor seededDoctor = seedDoctor(target);

        int numberOfUsers = 10;
        ConcurrentLinkedQueue<HttpStatusCode> statusPool = new ConcurrentLinkedQueue<>();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        try (ExecutorService executorService = Executors.newFixedThreadPool(numberOfUsers)) {
            for (int i = 0; i < numberOfUsers; i++) {
                executorService.execute(() -> {
                    try {
                        countDownLatch.await();
                        ValidPatient seededPatient = seedPatient();
                        ResponseEntity<String> response = restTemplate.exchange(
                                "/api/appointments", HttpMethod.POST,
                                new HttpEntity<>(bookingRequest(seededDoctor, LocalDateTime.of(target, LocalTime.of(9, 0)),
                                        "Test Notes"), patientHeaders(seededPatient.getPatientId())),
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

    private static AppointmentRequest bookingRequest(ValidDoctor doctor,
                                                                                     LocalDateTime dateTime,
                                                                                     String notes) {
        return AppointmentRequest.builder()
                .doctorId(doctor.getDoctorId())
                .dateTime(dateTime)
                .notes(notes)
                .type(AppointmentType.VACCINATION)
                .build();
    }

    @Test
    public void createAppointment_signWithUntrustedKeypair_returns401() throws NoSuchAlgorithmException {
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor seededDoctor = seedDoctor(target);
        ValidPatient seededPatient = seedPatient();

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        RSAPrivateKey untrustedPrivateKey = (RSAPrivateKey) gen.generateKeyPair().getPrivate();
        HttpHeaders headers = bearerHeaders(TestJwtFactory.patientToken(
                seededPatient.getPatientId(), untrustedPrivateKey));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/appointments", HttpMethod.POST,
                new HttpEntity<>(bookingRequest(seededDoctor, LocalDateTime.of(target, LocalTime.of(9, 0)), "Test Notes"),
                        headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // The full-stack proof for ADR-004's role enforcement: a DOCTOR token is signed by the
    // same private key as a PATIENT token (shared key pair), so signature validation alone
    // cannot reject it. Only SecurityConfig's hasRole("PATIENT") - driven by the role claim -
    // does, and this hits it through the real filter chain rather than a mocked Jwt.
    @Test
    void createAppointment_doctorToken_returns403() {
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor seededDoctor = seedDoctor(target);

        HttpHeaders headers = bearerHeaders(
                TestJwtFactory.doctorToken(UUID.randomUUID(), (RSAPrivateKey) KEY_PAIR.getPrivate()));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/appointments", HttpMethod.POST,
                new HttpEntity<>(bookingRequest(seededDoctor, LocalDateTime.of(target, LocalTime.of(9, 0)), "Test Notes"),
                        headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void createAppointment_pastSlot_returns400SlotInThePast() throws Exception {
        // An aligned slot inside opening hours on a past date: only NotInPastRule rejects it.
        LocalDate pastDate = LocalDate.now().minusWeeks(1);
        ValidDoctor seededDoctor = seedDoctor(pastDate);
        ValidPatient patient = seedPatient();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/appointments", HttpMethod.POST,
                new HttpEntity<>(bookingRequest(seededDoctor, LocalDateTime.of(pastDate, LocalTime.of(9, 0)), "Past slot"),
                        patientHeaders(patient.getPatientId())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = new ObjectMapper().readTree(response.getBody());
        assertThat(problem.get("status").asInt()).isEqualTo(400);
        assertThat(problem.get("title").asText()).isEqualTo("Slot In The Past");
    }

    @Test
    void cancelAppointment_notOwner_returns403() throws Exception {
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor seededDoctor = seedDoctor(target);
        ValidPatient patientB = seedPatient();
        UUID appointmentId = book(patientB, seededDoctor, LocalDateTime.of(target, LocalTime.of(9, 0)),
                AppointmentType.VACCINATION);

        ValidPatient patientA = seedPatient();
        ResponseEntity<String> cancelResponse = putCancel(patientHeaders(patientA.getPatientId()), appointmentId);

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode problem = new ObjectMapper().readTree(cancelResponse.getBody());
        assertThat(problem.get("status").asInt()).isEqualTo(403);
        assertThat(problem.get("title").asText()).isEqualTo("Not Resource Owner");
        assertThat(problem.get("detail").asText()).contains(appointmentId.toString());
    }

    // Seeds a doctor and patient, books a slot a week out and returns what a cancel call needs.
    private record BookedAppointment(UUID id, HttpHeaders headers) {
        String cancelUrl() {
            return "/api/appointments/" + id + "/cancel";
        }
    }

    private BookedAppointment bookAppointmentForCancel() {
        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor seededDoctor = seedDoctor(target);
        ValidPatient patient = seedPatient();
        UUID id = book(patient, seededDoctor, LocalDateTime.of(target, LocalTime.of(9, 0)), AppointmentType.VACCINATION);
        return new BookedAppointment(id, patientHeaders(patient.getPatientId()));
    }

    private long cancelledEventCount(UUID appointmentId) {
        return outboxRows(appointmentId, "appointment.cancelled").size();
    }

    @Test
    void cancelAppointment_calledTwice_secondCallIsNoOpAndPublishesOneEvent() {
        BookedAppointment booked = bookAppointmentForCancel();

        ResponseEntity<AppointmentResponse> first = restTemplate.exchange(
                booked.cancelUrl(), HttpMethod.PUT, new HttpEntity<>(booked.headers()), AppointmentResponse.class);
        ResponseEntity<AppointmentResponse> second = restTemplate.exchange(
                booked.cancelUrl(), HttpMethod.PUT, new HttpEntity<>(booked.headers()), AppointmentResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(cancelledEventCount(booked.id())).isEqualTo(1);
    }

    @Test
    void cancelAppointment_concurrentCalls_allReturn200AndPublishOneEvent() throws Exception {
        BookedAppointment booked = bookAppointmentForCancel();

        // All cancels are released together; without the row lock several read "not cancelled"
        // and each writes its own appointment.cancelled outbox row.
        int numberOfCalls = 10;
        ConcurrentLinkedQueue<HttpStatusCode> statusPool = new ConcurrentLinkedQueue<>();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        try (ExecutorService executorService = Executors.newFixedThreadPool(numberOfCalls)) {
            for (int i = 0; i < numberOfCalls; i++) {
                executorService.execute(() -> {
                    try {
                        countDownLatch.await();
                        ResponseEntity<String> response = restTemplate.exchange(
                                booked.cancelUrl(), HttpMethod.PUT,
                                new HttpEntity<>(booked.headers()), String.class);
                        statusPool.add(response.getStatusCode());
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            countDownLatch.countDown();
        }

        assertThat(statusPool).hasSize(numberOfCalls).allMatch(s -> s.equals(HttpStatus.OK));
        assertThat(cancelledEventCount(booked.id())).isEqualTo(1);
    }

    @Test
    void cancelAppointment_rowHeldByAnotherTransaction_returns503ThenSucceedsOnceReleased() throws Exception {
        BookedAppointment booked = bookAppointmentForCancel();
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService holderExecutor = Executors.newSingleThreadExecutor()) {
            // Another transaction holds the appointment row, as a long-running writer would.
            Future<?> holder = holderExecutor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbcTemplate.queryForList("select id from appointments where id = ? for update", booked.id());
                        rowLocked.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();

            long startNanos = System.nanoTime();
            ResponseEntity<String> blocked = restTemplate.exchange(
                    booked.cancelUrl(), HttpMethod.PUT, new HttpEntity<>(booked.headers()), String.class);
            long waitedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);

            // The cancel gives up after the lock timeout (3s) instead of waiting for the holder.
            assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(new ObjectMapper().readTree(blocked.getBody()).get("title").asText()).isEqualTo("Appointment Busy");
            assertThat(waitedSeconds).isLessThan(15);
            assertThat(cancelledEventCount(booked.id())).isZero();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        ResponseEntity<AppointmentResponse> retry = restTemplate.exchange(
                booked.cancelUrl(), HttpMethod.PUT, new HttpEntity<>(booked.headers()), AppointmentResponse.class);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(cancelledEventCount(booked.id())).isEqualTo(1);
    }

    @Test
    void cancelAppointment_nonexistentId_returns404() throws Exception {
        ValidPatient seededPatient = seedPatient();

        UUID nonexistentId = UUID.randomUUID();
        ResponseEntity<String> response = putCancel(patientHeaders(seededPatient.getPatientId()), nonexistentId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode problem = new ObjectMapper().readTree(response.getBody());
        assertThat(problem.get("status").asInt()).isEqualTo(404);
        assertThat(problem.get("title").asText()).isEqualTo("Appointment Not Found");
        assertThat(problem.get("detail").asText()).contains(nonexistentId.toString());
    }
}
