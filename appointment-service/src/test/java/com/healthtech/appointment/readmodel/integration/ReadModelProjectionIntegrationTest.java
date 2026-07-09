package com.healthtech.appointment.readmodel.integration;

import com.healthtech.appointment.event.DoctorRegistered;
import com.healthtech.appointment.event.OpeningHoursPayload;
import com.healthtech.appointment.event.PatientRegistered;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.readmodel.ValidPatient;
import com.healthtech.appointment.readmodel.ValidPatientRepository;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class ReadModelProjectionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    @ServiceConnection
    static ConfluentKafkaContainer kafkaContainer = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.0")
            .withStartupTimeout(Duration.ofMinutes(3));

    // KafkaConsumerConfig builds its ConsumerFactory from the literal "spring.kafka.bootstrap-servers"
    // property via @Value, bypassing the KafkaConnectionDetails bean that @ServiceConnection relies on.
    // The property must be set explicitly so the app's consumers actually point at this container.
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }

    @Autowired
    ValidPatientRepository validPatientRepository;
    @Autowired
    ValidDoctorRepository validDoctorRepository;

    private KafkaTemplate<String, Object> testProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // Generous timeouts: running the full suite starts several Postgres/Kafka containers
        // concurrently, and under host contention the broker can be slow to respond.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 180000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 60000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 300000);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    private <T> T await(Supplier<Optional<T>> supplier) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Optional<T> result = supplier.get();
            if (result.isPresent()) {
                return result.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Projection did not appear within timeout");
    }

    @Test
    void patientRegisteredEvent_consumed_createsValidPatientProjection() throws Exception {
        UUID patientId = UUID.randomUUID();
        PatientRegistered event = PatientRegistered.builder()
                .eventId(UUID.randomUUID())
                .patientId(patientId)
                .email("jane.doe@example.com")
                .firstName("Jane")
                .lastName("Doe")
                .registeredAt(LocalDateTime.now())
                .build();

        testProducer().send("patient.registered", event).get();

        ValidPatient projected = await(() -> validPatientRepository.findById(patientId));

        assertThat(projected.getPatientId()).isEqualTo(patientId);
        assertThat(projected.getEmail()).isEqualTo("jane.doe@example.com");
        assertThat(projected.getFirstName()).isEqualTo("Jane");
        assertThat(projected.getLastName()).isEqualTo("Doe");
    }

    @Test
    void doctorRegisteredEvent_consumed_createsValidDoctorProjection() throws Exception {
        UUID doctorId = UUID.randomUUID();
        OpeningHoursPayload openingHours = OpeningHoursPayload.builder()
                .dayOfWeek(java.time.DayOfWeek.MONDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .build();
        DoctorRegistered event = DoctorRegistered.builder()
                .eventId(UUID.randomUUID())
                .doctorId(doctorId)
                .firstName("John")
                .lastName("Smith")
                .registeredAt(LocalDateTime.now())
                .openingHours(Set.of(openingHours))
                .build();

        testProducer().send("doctor.registered", event).get();

        ValidDoctor projected = await(() -> validDoctorRepository.findById(doctorId));

        assertThat(projected.getDoctorId()).isEqualTo(doctorId);
        assertThat(projected.getFirstName()).isEqualTo("John");
        assertThat(projected.getLastName()).isEqualTo("Smith");
        assertThat(projected.getOpeningHours()).hasSize(1);
        assertThat(projected.getOpeningHours().iterator().next().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
    }
}
