package com.healthtech.doctor.outbox;

import com.healthtech.doctor.domain.Language;
import com.healthtech.doctor.dto.AddressDto;
import com.healthtech.doctor.dto.DoctorAuthResponse;
import com.healthtech.doctor.dto.DoctorRegistrationRequest;
import com.healthtech.doctor.dto.OpeningHoursDto;
import com.healthtech.doctor.repository.DoctorRepository;
import com.healthtech.doctor.repository.SpecialtyRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Simulates the broker going down and recovering. Uses the low-level Docker API to pause/unpause
// the SAME container: pause freezes the broker process via the cgroup freezer in place, so the
// port mapping never changes (unlike Testcontainers' own stop(), which tears the container down
// and hands back a differently-port-mapped one on restart) and there is no Kafka process
// reinitialization to race against. A paused broker also more realistically simulates an
// unresponsive node than an immediate connection refusal, exercising the bounded max.block.ms /
// delivery.timeout.ms producer config rather than a fast-fail path.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext
class OutboxRelayKafkaOutageIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    @ServiceConnection
    static ConfluentKafkaContainer kafkaContainer = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.0")
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }

    @Autowired
    DoctorRepository doctorRepository;
    @Autowired
    SpecialtyRepository specialtyRepository;
    @Autowired
    OutboxRepository outboxRepository;
    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void register_survivesKafkaOutage_thenDrainsOnRecovery() throws Exception {
        String containerId = kafkaContainer.getContainerId();
        var dockerClient = DockerClientFactory.instance().client();

        dockerClient.pauseContainerCmd(containerId).exec();
        try {
            var specialty = specialtyRepository.findByName("General Practice").orElseThrow();
            DoctorRegistrationRequest request = DoctorRegistrationRequest.builder()
                    .firstName("Kaf")
                    .lastName("Outage")
                    .email("kaf.outage@example.com")
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
                    .languages(Set.of(Language.ENGLISH))
                    .build();

            ResponseEntity<DoctorAuthResponse> response = restTemplate.postForEntity(
                    "/api/doctors/register", request, DoctorAuthResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            var doctor = doctorRepository.findByEmail("kaf.outage@example.com").orElseThrow();
            OutboxMessage row = outboxRepository.findAll().stream()
                    .filter(m -> m.getAggregateId().equals(doctor.getId().toString()))
                    .findFirst().orElseThrow();
            assertThat(row.getPublishedAt()).isNull();
        } finally {
            dockerClient.unpauseContainerCmd(containerId).exec();
        }

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer()) {
            consumer.subscribe(List.of("doctor.registered"));

            var doctor = doctorRepository.findByEmail("kaf.outage@example.com").orElseThrow();

            Awaitility.await()
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        OutboxMessage refreshed = outboxRepository.findById(
                                outboxRepository.findAll().stream()
                                        .filter(m -> m.getAggregateId().equals(doctor.getId().toString()))
                                        .findFirst().orElseThrow().getId()
                        ).orElseThrow();
                        assertThat(refreshed.getPublishedAt()).isNotNull();
                    });

            ConsumerRecord<String, String> record = Awaitility.await()
                    .atMost(Duration.ofSeconds(20))
                    .until(() -> {
                        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
                        for (ConsumerRecord<String, String> r : records) {
                            if (doctor.getId().toString().equals(r.key())) {
                                return r;
                            }
                        }
                        return null;
                    }, java.util.Objects::nonNull);

            assertThat(record.topic()).isEqualTo("doctor.registered");
        }
    }
}
