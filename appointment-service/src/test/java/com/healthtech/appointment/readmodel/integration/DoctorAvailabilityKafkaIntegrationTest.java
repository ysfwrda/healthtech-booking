package com.healthtech.appointment.readmodel.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.domain.Appointment;
import com.healthtech.appointment.domain.AppointmentStatus;
import com.healthtech.appointment.domain.AppointmentType;
import com.healthtech.appointment.domain.ConflictReason;
import com.healthtech.appointment.outbox.OutboxMessage;
import com.healthtech.appointment.outbox.OutboxRepository;
import com.healthtech.appointment.readmodel.OpeningHours;
import com.healthtech.appointment.readmodel.ValidDoctor;
import com.healthtech.appointment.readmodel.ValidDoctorRepository;
import com.healthtech.appointment.repository.AppointmentRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

// doctor.availability-updated and doctor.registered over a real broker: the listeners, the flags and the relay of
// appointment.reschedule-required. Payloads are plain maps so they can carry fields this service does not know.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DoctorAvailabilityKafkaIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    @ServiceConnection
    static ConfluentKafkaContainer kafkaContainer = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.0")
            .withStartupTimeout(Duration.ofMinutes(3));

    // KafkaConsumerConfig reads the literal property, bypassing the connection details of @ServiceConnection.
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }

    private static final LocalDate NEXT_WEEK = LocalDate.now().plusWeeks(1);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired ValidDoctorRepository validDoctorRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired OutboxRepository outboxRepository;

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
        for (int i = 0; i < 300; i++) {
            Optional<T> result = supplier.get();
            if (result.isPresent()) {
                return result.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Condition did not hold within the timeout");
    }

    private ValidDoctor seedDoctor() {
        return validDoctorRepository.save(ValidDoctor.builder().doctorId(UUID.randomUUID())
                .firstName("Kafka").lastName("Doctor")
                .openingHours(Set.of(OpeningHours.builder().dayOfWeek(NEXT_WEEK.getDayOfWeek())
                        .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).build()))
                .availabilityVersion(1L).build());
    }

    private Appointment seedAppointment(ValidDoctor doctor, int hour) {
        return appointmentRepository.saveAndFlush(Appointment.builder().patientId(UUID.randomUUID())
                .doctorId(doctor.getDoctorId()).dateTime(LocalDateTime.of(NEXT_WEEK, LocalTime.of(hour, 0)))
                .duration(30).type(AppointmentType.FOLLOW_UP).status(AppointmentStatus.CONFIRMED).build());
    }

    // The payload as doctor-service writes it, plus a field this service has never heard of.
    private Map<String, Object> snapshotJson(UUID doctorId, long version, int endHour) {
        Map<String, Object> hours = Map.of("dayOfWeek", NEXT_WEEK.getDayOfWeek().name(),
                "startTime", "09:00:00", "endTime", String.format("%02d:00:00", endHour));
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("doctorId", doctorId.toString());
        payload.put("openingHours", List.of(hours));
        payload.put("vacations", List.of());
        payload.put("version", version);
        payload.put("aFieldFromTheFuture", "ignored");
        return payload;
    }

    private Optional<OutboxMessage> rescheduleRow(UUID appointmentId) {
        return outboxRepository.findAll().stream()
                .filter(m -> m.getTopic().equals("appointment.reschedule-required")
                        && m.getAggregateId().equals(appointmentId.toString()))
                .findFirst();
    }

    private List<ConsumerRecord<String, String>> readTopic(String topic, String key) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new java.util.ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (found.isEmpty() && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
            }
        }
        return found;
    }

    @Test
    void availabilityUpdatedOnKafka_flagsTheAppointmentAndRelaysRescheduleRequiredToTheBroker() throws Exception {
        // Arrange
        ValidDoctor doctor = seedDoctor();
        Appointment afternoon = seedAppointment(doctor, 14);
        Appointment morning = seedAppointment(doctor, 10);

        // Act
        testProducer().send("doctor.availability-updated", doctor.getDoctorId().toString(),
                snapshotJson(doctor.getDoctorId(), 2, 12)).get();

        // Assert
        await(() -> appointmentRepository.findById(afternoon.getId())
                .filter(a -> a.getConflictReason() == ConflictReason.OUTSIDE_OPENING_HOURS));
        assertThat(appointmentRepository.findById(morning.getId()).orElseThrow().getConflictReason()).isNull();
        ValidDoctor projected = validDoctorRepository.findById(doctor.getDoctorId()).orElseThrow();
        assertThat(projected.getAvailabilityVersion()).isEqualTo(2);
        assertThat(projected.getOpeningHours().iterator().next().getEndTime()).isEqualTo(LocalTime.of(12, 0));
        OutboxMessage row = await(() -> rescheduleRow(afternoon.getId()));
        assertThat(row.getTopic()).isEqualTo("appointment.reschedule-required");
        List<ConsumerRecord<String, String>> relayed = readTopic("appointment.reschedule-required", afternoon.getId().toString());
        assertThat(relayed).hasSize(1);
        JsonNode event = objectMapper.readTree(relayed.get(0).value());
        assertThat(event.get("appointmentId").asText()).isEqualTo(afternoon.getId().toString());
        assertThat(event.get("reason").asText()).isEqualTo("OUTSIDE_OPENING_HOURS");
    }

    @Test
    void theSameSnapshotDeliveredTwiceOnKafka_queuesOneRescheduleRequired() throws Exception {
        // Arrange
        ValidDoctor doctor = seedDoctor();
        Appointment afternoon = seedAppointment(doctor, 14);
        Map<String, Object> snapshot = snapshotJson(doctor.getDoctorId(), 2, 12);
        KafkaTemplate<String, Object> producer = testProducer();

        // Act: same key, so both land on one partition and are consumed in order
        producer.send("doctor.availability-updated", doctor.getDoctorId().toString(), snapshot).get();
        producer.send("doctor.availability-updated", doctor.getDoctorId().toString(), snapshot).get();
        await(() -> appointmentRepository.findById(afternoon.getId())
                .filter(a -> a.getConflictReason() != null));
        Thread.sleep(3000);

        // Assert
        long rows = outboxRepository.findAll().stream()
                .filter(m -> m.getTopic().equals("appointment.reschedule-required")
                        && m.getAggregateId().equals(afternoon.getId().toString()))
                .count();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void aNewerSnapshotThatFitsAgain_clearsTheFlagOnKafka() throws Exception {
        // Arrange
        ValidDoctor doctor = seedDoctor();
        Appointment afternoon = seedAppointment(doctor, 14);
        KafkaTemplate<String, Object> producer = testProducer();
        producer.send("doctor.availability-updated", doctor.getDoctorId().toString(),
                snapshotJson(doctor.getDoctorId(), 2, 12)).get();
        await(() -> appointmentRepository.findById(afternoon.getId()).filter(a -> a.getConflictReason() != null));

        // Act
        producer.send("doctor.availability-updated", doctor.getDoctorId().toString(),
                snapshotJson(doctor.getDoctorId(), 3, 17)).get();

        // Assert
        await(() -> appointmentRepository.findById(afternoon.getId()).filter(a -> a.getConflictReason() == null));
    }

    @Test
    void doctorRegisteredWithAnUnknownFieldOnKafka_updatesTheKnownDoctorsNameAndKeepsItsHours() throws Exception {
        // Arrange
        ValidDoctor doctor = seedDoctor();
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("doctorId", doctor.getDoctorId().toString());
        payload.put("firstName", "Renamed");
        payload.put("lastName", "Doctor");
        payload.put("autoConfirmBookings", true);
        payload.put("openingHours", List.of(Map.of("dayOfWeek", "SUNDAY", "startTime", "01:00:00", "endTime", "02:00:00")));
        payload.put("aFieldFromTheFuture", 42);

        // Act
        testProducer().send("doctor.registered", doctor.getDoctorId().toString(), payload).get();

        // Assert
        ValidDoctor updated = await(() -> validDoctorRepository.findById(doctor.getDoctorId())
                .filter(d -> "Renamed".equals(d.getFirstName())));
        assertThat(updated.isAutoConfirmBookings()).isTrue();
        assertThat(updated.getOpeningHours()).hasSize(1);
        assertThat(updated.getOpeningHours().iterator().next().getStartTime()).isEqualTo(LocalTime.of(9, 0));
    }
}
