package com.healthtech.doctor.config;

import com.healthtech.doctor.outbox.OutboxMessage;
import com.healthtech.doctor.outbox.OutboxRepository;
import com.healthtech.doctor.repository.DoctorRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Re-seeds a removed demo doctor while the Kafka container is paused in place (the port mapping
// survives), the same outage OutboxRelayKafkaOutageIntegrationTest simulates for registration.
@SpringBootTest
@Testcontainers
@DirtiesContext
class DoctorSeederKafkaOutageIntegrationTest {

    private static final String DOCTOR_REGISTERED_TOPIC = "doctor.registered";

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
    DoctorSeeder doctorSeeder;
    @Autowired
    DoctorRepository doctorRepository;
    @Autowired
    OutboxRepository outboxRepository;

    @Test
    void run_whileKafkaIsDown_leavesUnpublishedDoctorRegisteredOutboxRow() {
        // Arrange
        String email = "anna.weber@demo.healthtech.com";
        removeDemoDoctor(email);

        pauseKafka();
        try {
            // Act
            doctorSeeder.run();

            // Assert
            UUID doctorId = doctorRepository.findByEmail(email).orElseThrow().getId();
            assertThat(outboxRowsFor(doctorId)).singleElement().satisfies(row -> {
                assertThat(row.getTopic()).isEqualTo(DOCTOR_REGISTERED_TOPIC);
                assertThat(row.getPublishedAt()).isNull();
            });
        } finally {
            unpauseKafka();
        }
    }

    @Test
    void run_whileKafkaIsDown_publishesDoctorRegisteredOnceKafkaRecovers() {
        // Arrange
        String email = "mehmet.yilmaz@demo.healthtech.com";
        removeDemoDoctor(email);
        pauseKafka();
        boolean kafkaPaused = true;
        try (Consumer<String, String> consumer = testConsumer()) {
            doctorSeeder.run();
            UUID doctorId = doctorRepository.findByEmail(email).orElseThrow().getId();

            // Act
            unpauseKafka();
            kafkaPaused = false;

            // Assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> assertThat(outboxRowsFor(doctorId))
                            .singleElement()
                            .satisfies(row -> assertThat(row.getPublishedAt()).isNotNull()));
            ConsumerRecord<String, String> record = Awaitility.await()
                    .atMost(Duration.ofSeconds(20))
                    .until(() -> {
                        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
                        for (ConsumerRecord<String, String> r : records) {
                            if (doctorId.toString().equals(r.key())) {
                                return r;
                            }
                        }
                        return null;
                    }, java.util.Objects::nonNull);
            assertThat(record.topic()).isEqualTo(DOCTOR_REGISTERED_TOPIC);
        } finally {
            if (kafkaPaused) {
                unpauseKafka();
            }
        }
    }

    @Test
    void run_doctorsAlreadyExist_writesNoAdditionalOutboxRow() {
        // Arrange
        long doctorRegisteredRowsBefore = doctorRegisteredRowCount();

        // Act
        doctorSeeder.run();

        // Assert
        assertThat(doctorRegisteredRowCount()).isEqualTo(doctorRegisteredRowsBefore);
    }

    private void pauseKafka() {
        DockerClientFactory.instance().client().pauseContainerCmd(kafkaContainer.getContainerId()).exec();
    }

    private void unpauseKafka() {
        DockerClientFactory.instance().client().unpauseContainerCmd(kafkaContainer.getContainerId()).exec();
    }

    private void removeDemoDoctor(String email) {
        UUID doctorId = doctorRepository.findByEmail(email).orElseThrow().getId();
        doctorRepository.deleteById(doctorId);
    }

    private List<OutboxMessage> outboxRowsFor(UUID doctorId) {
        return outboxRepository.findAll().stream()
                .filter(m -> m.getAggregateId().equals(doctorId.toString()))
                .toList();
    }

    private long doctorRegisteredRowCount() {
        return outboxRepository.findAll().stream()
                .filter(m -> m.getTopic().equals(DOCTOR_REGISTERED_TOPIC))
                .count();
    }

    private Consumer<String, String> testConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
        consumer.subscribe(List.of(DOCTOR_REGISTERED_TOPIC));
        return consumer;
    }
}
