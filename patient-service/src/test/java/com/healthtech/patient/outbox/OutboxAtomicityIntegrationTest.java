package com.healthtech.patient.outbox;

import com.healthtech.patient.domain.InsuranceType;
import com.healthtech.patient.dto.AuthResponse;
import com.healthtech.patient.dto.RegisterRequest;
import com.healthtech.patient.repository.PatientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

// The relay's fixed delay is pushed out to an hour so it never fires during this test class,
// keeping "still unpublished" assertions deterministic instead of racing a live scheduler.
// No Kafka container is needed: ProducerFactory bean creation is lazy and never actually
// connects unless something sends, which nothing does here.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "outbox.relay.fixed-delay-ms=3600000")
@Testcontainers
class OutboxAtomicityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Autowired
    PatientRepository patientRepository;
    @Autowired
    OutboxRepository outboxRepository;
    @Autowired
    TestRestTemplate restTemplate;

    private RegisterRequest.RegisterRequestBuilder validRequestBuilder(String username, String email) {
        return RegisterRequest.builder()
                .firstName("Ada")
                .lastName("Lovelace")
                .username(username)
                .password("Sup3rSecret!")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .email(email)
                .insuranceType(InsuranceType.STATUTORY);
    }

    @Test
    void register_success_writesExactlyOneUnpublishedOutboxRow() {
        RegisterRequest request = validRequestBuilder("atomicityuser", "atomicity@example.com").build();

        ResponseEntity<AuthResponse> response = restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var patient = patientRepository.findByUsername("atomicityuser").orElseThrow();
        var rows = outboxRepository.findAll().stream()
                .filter(m -> m.getAggregateId().equals(patient.getId().toString()))
                .toList();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTopic()).isEqualTo("patient.registered");
        assertThat(rows.get(0).getPublishedAt()).isNull();
    }

    @Test
    void register_rollsBackOnDuplicateUsername_writesNoAdditionalOutboxRow() {
        RegisterRequest first = validRequestBuilder("dupeuser", "dupe1@example.com").build();
        ResponseEntity<AuthResponse> firstResponse = restTemplate.postForEntity("/api/auth/register", first, AuthResponse.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        long countAfterFirst = outboxRepository.count();

        RegisterRequest duplicate = validRequestBuilder("dupeuser", "dupe2@example.com").build();
        ResponseEntity<String> secondResponse = restTemplate.postForEntity("/api/auth/register", duplicate, String.class);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(outboxRepository.count()).isEqualTo(countAfterFirst);
    }
}
