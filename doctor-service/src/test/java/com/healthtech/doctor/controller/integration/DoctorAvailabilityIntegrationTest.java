package com.healthtech.doctor.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.doctor.domain.Language;
import com.healthtech.doctor.domain.Specialty;
import com.healthtech.doctor.dto.AddressDto;
import com.healthtech.doctor.dto.DoctorAuthResponse;
import com.healthtech.doctor.dto.DoctorRegistrationRequest;
import com.healthtech.doctor.dto.OpeningHoursDto;
import com.healthtech.doctor.outbox.OutboxMessage;
import com.healthtech.doctor.outbox.OutboxRepository;
import com.healthtech.doctor.repository.DoctorRepository;
import com.healthtech.doctor.repository.SpecialtyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

// Real Postgres and the real security rules; the relay delay keeps outbox rows unpublished.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "outbox.relay.fixed-delay-ms=3600000")
@Testcontainers
class DoctorAvailabilityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    private static final String TOPIC = "doctor.availability-updated";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired TestRestTemplate restTemplate;
    @Autowired SpecialtyRepository specialtyRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired OutboxRepository outboxRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private record Registered(UUID id, String token) {}

    private Registered registerDoctor() {
        Specialty specialty = specialtyRepository.findByName("General Practice").orElseThrow();
        DoctorRegistrationRequest request = DoctorRegistrationRequest.builder()
                .firstName("Ada").lastName("Lovelace")
                .email("avail-" + UUID.randomUUID() + "@example.com")
                .password("secret123").phoneNumber("+491234567")
                .address(AddressDto.builder().street("Main St").houseNumber("1").postalCode("12345")
                        .city("Berlin").country("Germany").build())
                .specialtyIds(Set.of(specialty.getId()))
                .openingHours(Set.of(OpeningHoursDto.builder().dayOfWeek(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).build()))
                .languages(Set.of(Language.ENGLISH))
                .build();
        ResponseEntity<DoctorAuthResponse> response =
                restTemplate.postForEntity("/api/doctors/register", request, DoctorAuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new Registered(response.getBody().getId(), response.getBody().getToken());
    }

    private HttpEntity<Object> withToken(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
        return restTemplate.exchange(path, method, withToken(token, body), String.class);
    }

    private List<OutboxMessage> availabilityRows(UUID doctorId) {
        return outboxRepository.findAll().stream()
                .filter(m -> TOPIC.equals(m.getTopic()) && m.getAggregateId().equals(doctorId.toString()))
                .toList();
    }

    private JsonNode payload(OutboxMessage row) throws Exception {
        return JSON.readTree(row.getPayload());
    }

    private Map<String, Object> hours(String day, String start, String end) {
        return Map.of("dayOfWeek", day, "startTime", start, "endTime", end);
    }

    @Test
    void replaceOpeningHours_savesThemReturnsThemAndWritesOneSnapshotRow() throws Exception {
        // Arrange
        Registered doctor = registerDoctor();

        // Act
        ResponseEntity<String> put = call(HttpMethod.PUT, "/api/doctors/me/availability/opening-hours", doctor.token(),
                Map.of("openingHours", List.of(hours("TUESDAY", "10:00", "12:00"), hours("TUESDAY", "13:00", "16:00"))));
        ResponseEntity<String> get = call(HttpMethod.GET, "/api/doctors/me/availability", doctor.token(), null);

        // Assert
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode available = JSON.readTree(get.getBody());
        assertThat(available.get("openingHours")).hasSize(2);
        assertThat(availabilityRows(doctor.id())).hasSize(1);
        JsonNode event = payload(availabilityRows(doctor.id()).get(0));
        assertThat(event.get("version").asLong()).isEqualTo(1);
        assertThat(event.get("openingHours")).hasSize(2);
        assertThat(event.get("vacations")).isEmpty();
        assertThat(availabilityRows(doctor.id()).get(0).getPublishedAt()).isNull();
    }

    @Test
    void vacationAddedThenRemoved_writesConsecutiveVersionsAndTheSnapshotFollows() throws Exception {
        // Arrange
        Registered doctor = registerDoctor();
        LocalDate start = LocalDate.now().plusDays(10);

        // Act
        ResponseEntity<String> created = call(HttpMethod.POST, "/api/doctors/me/availability/vacations", doctor.token(),
                Map.of("startDate", start.toString(), "endDate", start.plusDays(3).toString()));
        String vacationId = JSON.readTree(created.getBody()).get("id").asText();
        ResponseEntity<String> deleted = call(HttpMethod.DELETE,
                "/api/doctors/me/availability/vacations/" + vacationId, doctor.token(), null);

        // Assert
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        List<JsonNode> events = new ArrayList<>();
        for (OutboxMessage row : availabilityRows(doctor.id())) {
            events.add(payload(row));
        }
        events.sort((a, b) -> Long.compare(a.get("version").asLong(), b.get("version").asLong()));
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("version").asLong()).isEqualTo(1);
        assertThat(events.get(0).get("vacations")).hasSize(1);
        assertThat(events.get(0).get("vacations").get(0).get("startDate").asText()).isEqualTo(start.toString());
        assertThat(events.get(1).get("version").asLong()).isEqualTo(2);
        assertThat(events.get(1).get("vacations")).isEmpty();
    }

    @Test
    void rejectedVacation_writesNoOutboxRowAndKeepsTheVersion() {
        // Arrange
        Registered doctor = registerDoctor();
        LocalDate yesterday = LocalDate.now().minusDays(1);

        // Act
        ResponseEntity<String> response = call(HttpMethod.POST, "/api/doctors/me/availability/vacations", doctor.token(),
                Map.of("startDate", yesterday.minusDays(2).toString(), "endDate", yesterday.toString()));

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(availabilityRows(doctor.id())).isEmpty();
        assertThat(doctorRepository.findById(doctor.id()).orElseThrow().getAvailabilityVersion()).isZero();
    }

    @Test
    void rejectedOpeningHours_writesNoOutboxRow() {
        // Arrange
        Registered doctor = registerDoctor();

        // Act
        ResponseEntity<String> response = call(HttpMethod.PUT, "/api/doctors/me/availability/opening-hours", doctor.token(),
                Map.of("openingHours", List.of(hours("MONDAY", "09:15", "12:00"))));

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(availabilityRows(doctor.id())).isEmpty();
    }

    @Test
    void removeVacation_ofAnotherDoctor_returns404AndKeepsTheVacation() throws Exception {
        // Arrange
        Registered owner = registerDoctor();
        Registered other = registerDoctor();
        LocalDate start = LocalDate.now().plusDays(5);
        String vacationId = JSON.readTree(call(HttpMethod.POST, "/api/doctors/me/availability/vacations", owner.token(),
                Map.of("startDate", start.toString(), "endDate", start.toString())).getBody()).get("id").asText();

        // Act
        ResponseEntity<String> response = call(HttpMethod.DELETE,
                "/api/doctors/me/availability/vacations/" + vacationId, other.token(), null);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode ownersAvailability = JSON.readTree(
                call(HttpMethod.GET, "/api/doctors/me/availability", owner.token(), null).getBody());
        assertThat(ownersAvailability.get("vacations")).hasSize(1);
        assertThat(availabilityRows(other.id())).isEmpty();
    }

    @Test
    void concurrentEdits_bothSucceedWithConsecutiveVersions() throws Exception {
        // Arrange
        Registered doctor = registerDoctor();
        LocalDate day = LocalDate.now().plusDays(20);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);

        // Act
        List<Future<ResponseEntity<String>>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            LocalDate start = day.plusDays(i * 10L);
            results.add(pool.submit(() -> {
                go.await();
                return call(HttpMethod.POST, "/api/doctors/me/availability/vacations", doctor.token(),
                        Map.of("startDate", start.toString(), "endDate", start.plusDays(1).toString()));
            }));
        }
        go.countDown();
        for (Future<ResponseEntity<String>> result : results) {
            assertThat(result.get().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        pool.shutdown();

        // Assert
        List<Long> versions = new ArrayList<>();
        for (OutboxMessage row : availabilityRows(doctor.id())) {
            versions.add(payload(row).get("version").asLong());
        }
        assertThat(versions).containsExactlyInAnyOrder(1L, 2L);
        for (OutboxMessage row : availabilityRows(doctor.id())) {
            long version = payload(row).get("version").asLong();
            assertThat(payload(row).get("vacations")).hasSize((int) version);
        }
    }

    @Test
    void availabilityEndpoints_noTokenOrUnsignedToken_return401() {
        // Arrange
        String unsignedToken = io.jsonwebtoken.Jwts.builder().subject(UUID.randomUUID().toString())
                .claim("role", "PATIENT").compact();

        // Act
        ResponseEntity<String> noToken = call(HttpMethod.GET, "/api/doctors/me/availability", null, null);
        ResponseEntity<String> unsignedTokenResponse = call(HttpMethod.GET, "/api/doctors/me/availability", unsignedToken, null);

        // Assert
        assertThat(noToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unsignedTokenResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void availabilityVersionColumn_hasADatabaseDefaultSoExistingRowsUpgrade() {
        // Act
        String columnDefault = jdbcTemplate.queryForObject(
                "select column_default from information_schema.columns "
                        + "where table_name = 'doctors' and column_name = 'availability_version'", String.class);
        String nullable = jdbcTemplate.queryForObject(
                "select is_nullable from information_schema.columns "
                        + "where table_name = 'doctors' and column_name = 'availability_version'", String.class);

        // Assert
        assertThat(columnDefault).startsWith("0");
        assertThat(nullable).isEqualTo("NO");
    }
}
