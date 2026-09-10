package com.healthtech.patient.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.patient.domain.InsuranceType;
import com.healthtech.patient.dto.AuthResponse;
import com.healthtech.patient.dto.RegisterRequest;
import com.healthtech.patient.outbox.OutboxRepository;
import com.healthtech.patient.repository.PatientRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// @DirtiesContext: without it the relay's background scheduler keeps running after this
// class's containers are torn down, spamming connection-refused against dead containers for
// the rest of the suite.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext
public class AuthIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    @ServiceConnection
    static ConfluentKafkaContainer kafkaContainer = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.0")
            .withStartupTimeout(Duration.ofMinutes(3));

    // OutboxKafkaConfig builds its ProducerFactory from the literal "spring.kafka.bootstrap-servers"
    // property via @Value, bypassing the KafkaConnectionDetails bean that @ServiceConnection relies on.
    // The property must be set explicitly so the relay's producer actually points at this container.
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

    private RegisterRequest.RegisterRequestBuilder validRequestBuilder(String username, String email) {
        return RegisterRequest.builder()
                .firstName("Jane")
                .lastName("Doe")
                .username(username)
                .password("Sup3rSecret!")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .email(email)
                .insuranceType(InsuranceType.STATUTORY);
    }

    private Consumer<String, String> testConsumer(String topic) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
        consumer.subscribe(java.util.List.of(topic));
        return consumer;
    }

    @Test
    void register_duplicateUsername_returns409AndSingleRow() throws Exception {
        RegisterRequest first = validRequestBuilder("janedoe", "jane.doe@example.com").build();
        ResponseEntity<AuthResponse> firstResponse = restTemplate.postForEntity("/api/auth/register", first, AuthResponse.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        RegisterRequest duplicate = validRequestBuilder("janedoe", "different.email@example.com").build();
        ResponseEntity<String> secondResponse = restTemplate.postForEntity("/api/auth/register", duplicate, String.class);

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = new ObjectMapper().readTree(secondResponse.getBody());
        assertThat(problem.get("status").asInt()).isEqualTo(409);
        assertThat(problem.get("title").asText()).isEqualTo("Username Already Taken");
        assertThat(problem.get("detail").asText()).isEqualTo("janedoe");

        assertThat(patientRepository.findAll().stream().filter(p -> p.getUsername().equals("janedoe")).count()).isEqualTo(1);
    }

    @Test
    void register_duplicateEmail_returns409AndSingleRow() throws Exception {
        RegisterRequest first = validRequestBuilder("firstuser", "shared@example.com").build();
        ResponseEntity<AuthResponse> firstResponse = restTemplate.postForEntity("/api/auth/register", first, AuthResponse.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        RegisterRequest duplicate = validRequestBuilder("seconduser", "shared@example.com").build();
        ResponseEntity<String> secondResponse = restTemplate.postForEntity("/api/auth/register", duplicate, String.class);

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = new ObjectMapper().readTree(secondResponse.getBody());
        assertThat(problem.get("status").asInt()).isEqualTo(409);
        assertThat(problem.get("title").asText()).isEqualTo("Email Already Registered");
        assertThat(problem.get("detail").asText()).isEqualTo("shared@example.com");

        assertThat(patientRepository.findAll().stream().filter(p -> p.getEmail().equals("shared@example.com")).count()).isEqualTo(1);
    }

    @Test
    void register_publishesPatientRegisteredEvent() {
        RegisterRequest request = validRequestBuilder("eventuser", "event.user@example.com").build();

        try (Consumer<String, String> consumer = testConsumer("patient.registered")) {
            ResponseEntity<AuthResponse> response = restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            var patient = patientRepository.findByUsername("eventuser").orElseThrow();

            ConsumerRecord<String, String> record = Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .until(() -> {
                        var records = consumer.poll(Duration.ofMillis(200));
                        for (ConsumerRecord<String, String> r : records) {
                            if (patient.getId().toString().equals(r.key())) {
                                return r;
                            }
                        }
                        return null;
                    }, java.util.Objects::nonNull);

            assertThat(record.topic()).isEqualTo("patient.registered");
            assertThat(record.key()).isEqualTo(patient.getId().toString());
            JsonNode event = new ObjectMapper().readTree(record.value());

            assertThat(event.get("patientId").asText()).isEqualTo(patient.getId().toString());
            assertThat(event.get("firstName").asText()).isEqualTo("Jane");
            assertThat(event.get("lastName").asText()).isEqualTo("Doe");
            assertThat(event.get("email").asText()).isEqualTo("event.user@example.com");

            Header correlationHeader = record.headers().lastHeader("X-Correlation-Id");
            assertThat(correlationHeader).isNotNull();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
