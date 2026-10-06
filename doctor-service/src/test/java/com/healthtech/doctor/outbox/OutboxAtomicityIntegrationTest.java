package com.healthtech.doctor.outbox;

import com.healthtech.doctor.domain.Language;
import com.healthtech.doctor.domain.Specialty;
import com.healthtech.doctor.dto.AddressDto;
import com.healthtech.doctor.dto.DoctorAuthResponse;
import com.healthtech.doctor.dto.DoctorRegistrationRequest;
import com.healthtech.doctor.dto.OpeningHoursDto;
import com.healthtech.doctor.event.DoctorRegistered;
import com.healthtech.doctor.repository.DoctorRepository;
import com.healthtech.doctor.repository.SpecialtyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// Relay delay pushed out to an hour so it never fires, keeping 'unpublished' assertions deterministic.
// No Kafka container: nothing sends, and DoctorSeeder's KafkaTemplate is mocked.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "outbox.relay.fixed-delay-ms=3600000")
@Testcontainers
class OutboxAtomicityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    @MockitoBean
    KafkaTemplate<String, DoctorRegistered> kafkaTemplate;

    @Autowired
    DoctorRepository doctorRepository;
    @Autowired
    SpecialtyRepository specialtyRepository;
    @Autowired
    OutboxRepository outboxRepository;
    @Autowired
    TestRestTemplate restTemplate;

    private DoctorRegistrationRequest.DoctorRegistrationRequestBuilder validRequestBuilder(String email) {
        Specialty specialty = specialtyRepository.findByName("General Practice").orElseThrow();

        return DoctorRegistrationRequest.builder()
                .firstName("Ada")
                .lastName("Lovelace")
                .email(email)
                .password("secret123")
                .phoneNumber("+491234567")
                .address(AddressDto.builder()
                        .street("Main St")
                        .houseNumber("1")
                        .postalCode("12345")
                        .city("Berlin")
                        .country("Germany")
                        .build())
                .specialtyIds(Set.of(specialty.getId()))
                .openingHours(Set.of(OpeningHoursDto.builder()
                        .dayOfWeek(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build()))
                .languages(Set.of(Language.ENGLISH));
    }

    @Test
    void register_success_writesExactlyOneUnpublishedOutboxRow() {
        // Arrange
        DoctorRegistrationRequest request = validRequestBuilder("atomicity.doctor@example.com").build();

        // Act
        ResponseEntity<DoctorAuthResponse> response = restTemplate.postForEntity(
                "/api/doctors/register", request, DoctorAuthResponse.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var doctorId = response.getBody().getId();
        var rows = outboxRepository.findAll().stream()
                .filter(m -> m.getAggregateId().equals(doctorId.toString()))
                .toList();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTopic()).isEqualTo("doctor.registered");
        assertThat(rows.get(0).getPublishedAt()).isNull();
    }

    @Test
    void register_rollsBackOnDuplicateEmail_writesNoAdditionalOutboxRow() {
        // Arrange: a doctor already registered under "dupe.doctor@example.com"
        DoctorRegistrationRequest first = validRequestBuilder("dupe.doctor@example.com").build();
        ResponseEntity<DoctorAuthResponse> firstResponse = restTemplate.postForEntity(
                "/api/doctors/register", first, DoctorAuthResponse.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long countAfterFirst = outboxRepository.count();
        DoctorRegistrationRequest duplicate = validRequestBuilder("dupe.doctor@example.com").build();

        // Act
        ResponseEntity<String> secondResponse = restTemplate.postForEntity(
                "/api/doctors/register", duplicate, String.class);

        // Assert
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outboxRepository.count()).isEqualTo(countAfterFirst);
    }
}
