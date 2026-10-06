package com.healthtech.patient.outbox;

import com.healthtech.patient.domain.InsuranceType;
import com.healthtech.patient.dto.AuthResponse;
import com.healthtech.patient.dto.RegisterRequest;
import com.healthtech.patient.repository.PatientRepository;
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

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Simulates a broker outage by pausing the Kafka container in place (the port mapping survives).
// A paused broker hangs rather than refuses, exercising the bounded producer timeouts.
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
    PatientRepository patientRepository;
    @Autowired
    OutboxRepository outboxRepository;
    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void register_whileKafkaIsDown_returns201AndLeavesUnpublishedOutboxRow() {
        // Arrange
        RegisterRequest request = RegisterRequest.builder()
                .firstName("Kaf")
                .lastName("Outage")
                .username("kafoutage")
                .password("Sup3rSecret!")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .email("kaf.outage@example.com")
                .insuranceType(InsuranceType.STATUTORY)
                .build();
        String containerId = kafkaContainer.getContainerId();
        var dockerClient = DockerClientFactory.instance().client();

        dockerClient.pauseContainerCmd(containerId).exec();
        try {
            // Act
            ResponseEntity<AuthResponse> response = restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            var patient = patientRepository.findByUsername("kafoutage").orElseThrow();
            OutboxMessage row = outboxRepository.findAll().stream()
                    .filter(m -> m.getAggregateId().equals(patient.getId().toString()))
                    .findFirst().orElseThrow();
            assertThat(row.getPublishedAt()).isNull();
        } finally {
            dockerClient.unpauseContainerCmd(containerId).exec();
        }
    }

    @Test
    void unpublishedRow_isPublishedOnceKafkaRecovers() {
        // Arrange
        String topic = "outage-recovery-test";
        String containerId = kafkaContainer.getContainerId();
        var dockerClient = DockerClientFactory.instance().client();
        OutboxMessage row = OutboxMessage.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID().toString())
                .topic(topic)
                .payload("{\"hello\":\"world\"}")
                .build();
        dockerClient.pauseContainerCmd(containerId).exec();
        outboxRepository.save(row);

        try (Consumer<String, String> consumer = testConsumer(topic)) {
            // Act
            dockerClient.unpauseContainerCmd(containerId).exec();

            // Assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> assertThat(
                            outboxRepository.findById(row.getId()).orElseThrow().getPublishedAt()).isNotNull());
            ConsumerRecord<String, String> record = Awaitility.await()
                    .atMost(Duration.ofSeconds(20))
                    .until(() -> {
                        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
                        for (ConsumerRecord<String, String> r : records) {
                            if (row.getAggregateId().equals(r.key())) {
                                return r;
                            }
                        }
                        return null;
                    }, java.util.Objects::nonNull);
            assertThat(record.topic()).isEqualTo(topic);
        }
    }

    private Consumer<String, String> testConsumer(String topic) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
        consumer.subscribe(List.of(topic));
        return consumer;
    }
}
