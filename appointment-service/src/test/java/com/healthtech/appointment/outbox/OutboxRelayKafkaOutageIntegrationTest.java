package com.healthtech.appointment.outbox;

import com.healthtech.appointment.controller.integration.TestJwtFactory;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.dto.AppointmentRequest;
import com.healthtech.appointment.dto.AppointmentResponse;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.readmodel.ValidPatient;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
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
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
@Import(OutboxRelayKafkaOutageIntegrationTest.TestSecurityConfig.class)
@Testcontainers
@DirtiesContext
class OutboxRelayKafkaOutageIntegrationTest {

    static final KeyPair KEY_PAIR;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KEY_PAIR = gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

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
    ValidPatientRepository validPatientRepository;
    @Autowired
    ValidDoctorRepository validDoctorRepository;
    @Autowired
    OutboxRepository outboxRepository;
    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void bookAppointment_survivesKafkaOutage_thenDrainsOnRecovery() throws Exception {
        String containerId = kafkaContainer.getContainerId();
        var dockerClient = DockerClientFactory.instance().client();

        LocalDate target = LocalDate.now().plusWeeks(1);
        ValidDoctor doctor = validDoctorRepository.save(ValidDoctor.builder()
                .doctorId(UUID.randomUUID())
                .firstName("Valid")
                .lastName("Doctor")
                .openingHours(Set.of(OpeningHours.builder()
                        .dayOfWeek(target.getDayOfWeek())
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build()))
                .build());
        ValidPatient patient = validPatientRepository.save(ValidPatient.builder()
                .patientId(UUID.randomUUID())
                .firstName("Kaf")
                .lastName("Outage")
                .build());

        dockerClient.pauseContainerCmd(containerId).exec();
        UUID appointmentId;
        try {
            String token = TestJwtFactory.patientToken(patient.getPatientId(), (RSAPrivateKey) KEY_PAIR.getPrivate());
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            AppointmentRequest request = AppointmentRequest.builder()
                    .doctorId(doctor.getDoctorId())
                    .dateTime(LocalDateTime.of(target, LocalTime.of(9, 0)))
                    .type(AppointmentType.VACCINATION)
                    .build();

            ResponseEntity<AppointmentResponse> response = restTemplate.exchange(
                    "/api/appointments", HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    AppointmentResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            appointmentId = response.getBody().getId();

            OutboxMessage row = outboxRepository.findAll().stream()
                    .filter(m -> m.getAggregateId().equals(appointmentId.toString()))
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
            consumer.subscribe(List.of("appointment.booked"));

            UUID finalAppointmentId = appointmentId;
            Awaitility.await()
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        OutboxMessage refreshed = outboxRepository.findAll().stream()
                                .filter(m -> m.getAggregateId().equals(finalAppointmentId.toString()))
                                .findFirst().orElseThrow();
                        assertThat(refreshed.getPublishedAt()).isNotNull();
                    });

            ConsumerRecord<String, String> record = Awaitility.await()
                    .atMost(Duration.ofSeconds(20))
                    .until(() -> {
                        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
                        for (ConsumerRecord<String, String> r : records) {
                            if (finalAppointmentId.toString().equals(r.key())) {
                                return r;
                            }
                        }
                        return null;
                    }, java.util.Objects::nonNull);

            assertThat(record.topic()).isEqualTo("appointment.booked");
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
