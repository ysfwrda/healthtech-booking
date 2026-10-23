package com.healthtech.appointment.config;

import com.healthtech.appointment.event.DoctorRegistered;
import com.healthtech.appointment.event.PatientRegistered;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.kafka.common.serialization.Deserializer;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The consumers must survive payloads that gain fields before this service is upgraded.
class KafkaConsumerConfigTest {

    private KafkaConsumerConfig config;

    @BeforeEach
    void setUp() {
        config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
    }

    @Test
    void doctorRegisteredFactory_payloadWithUnknownField_deserializesKnownFields() {
        // Arrange
        UUID doctorId = UUID.randomUUID();
        byte[] payload = ("{\"doctorId\":\"" + doctorId + "\",\"firstName\":\"Anna\","
                + "\"autoConfirmBookings\":true}").getBytes(StandardCharsets.UTF_8);
        Deserializer<DoctorRegistered> deserializer = valueDeserializer(
                config.doctorRegisteredKafkaListenerFactory().getConsumerFactory());

        // Act
        DoctorRegistered event = deserializer.deserialize("doctor.registered", payload);

        // Assert
        assertThat(event.getDoctorId()).isEqualTo(doctorId);
        assertThat(event.getFirstName()).isEqualTo("Anna");
    }

    @Test
    void patientRegisteredFactory_payloadWithUnknownField_deserializesKnownFields() {
        // Arrange
        UUID patientId = UUID.randomUUID();
        byte[] payload = ("{\"patientId\":\"" + patientId + "\",\"somethingNew\":1}")
                .getBytes(StandardCharsets.UTF_8);
        Deserializer<PatientRegistered> deserializer = valueDeserializer(
                config.patientRegisteredKafkaListenerFactory().getConsumerFactory());

        // Act
        PatientRegistered event = deserializer.deserialize("patient.registered", payload);

        // Assert
        assertThat(event.getPatientId()).isEqualTo(patientId);
    }

    @SuppressWarnings("unchecked")
    private static <T> Deserializer<T> valueDeserializer(ConsumerFactory<?, ?> factory) {
        return (Deserializer<T>) factory.getValueDeserializer();
    }
}
