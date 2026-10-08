package com.healthtech.doctor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.Duration;

// Relay delay pushed out to an hour: there is no broker here for it to publish the seeded rows to.
@SpringBootTest(properties = "outbox.relay.fixed-delay-ms=3600000")
class DoctorServiceApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Test
    void contextLoads() {
    }

}
