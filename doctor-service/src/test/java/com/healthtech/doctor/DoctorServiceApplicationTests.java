package com.healthtech.doctor;

import com.healthtech.doctor.event.DoctorRegistered;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.Duration;

@SpringBootTest
class DoctorServiceApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    // DoctorSeeder now runs at startup and publishes doctor.registered via this bean; mocking
    // it here avoids a real Kafka broker dependency (and the metadata-timeout block/failure
    // that would follow without one) for a test that only cares that the context loads.
    @MockitoBean
    KafkaTemplate<String, DoctorRegistered> kafkaTemplate;

    @Test
    void contextLoads() {
    }

}
