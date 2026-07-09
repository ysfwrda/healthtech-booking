package com.healthtech.notification.consumer.integration;

import com.healthtech.notification.domain.NotificationType;
import com.healthtech.notification.event.AppointmentBooked;
import com.healthtech.notification.repository.NotificationRepository;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class NotificationConsumerIntegrationTest {

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
    //
    // src/test/resources/application.yaml also hardcodes spring.jpa.database-platform to H2Dialect
    // for the module's H2-based unit tests. @ServiceConnection swaps the actual JDBC connection to
    // this Postgres container, but leaves that dialect override in place, so Hibernate silently
    // generates H2-flavored DDL against a real Postgres database unless it's overridden back here.
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    NotificationRepository notificationRepository;

    private KafkaTemplate<String, Object> testProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
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
        throw new AssertionError("Notification row did not appear within timeout");
    }

    @Test
    void appointmentBookedEvent_consumed_writesNotificationRow() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        LocalDateTime dateTime = LocalDateTime.now().plusDays(1);

        AppointmentBooked event = AppointmentBooked.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(appointmentId)
                .patientId(patientId)
                .patientName("Jane Doe")
                .patientEmail("jane.doe@example.com")
                .doctorId(doctorId)
                .doctorName("Dr. Smith")
                .type("VACCINATION")
                .duration(30)
                .dateTime(dateTime)
                .bookedAt(LocalDateTime.now())
                .build();

        testProducer().send("appointment.booked", event).get();

        var notification = await(() -> notificationRepository.findAll().stream()
                .filter(n -> n.getAppointmentId().equals(appointmentId))
                .findFirst());

        assertThat(notification.getAppointmentId()).isEqualTo(appointmentId);
        assertThat(notification.getPatientId()).isEqualTo(patientId);
        assertThat(notification.getDoctorId()).isEqualTo(doctorId);
        // The consumer hardcodes this to APPOINTMENT_BOOKED - it never reads event.getType()
        // (that's the appointment's medical type, e.g. VACCINATION, a different concept).
        assertThat(notification.getType()).isEqualTo(NotificationType.APPOINTMENT_BOOKED);
    }
}
