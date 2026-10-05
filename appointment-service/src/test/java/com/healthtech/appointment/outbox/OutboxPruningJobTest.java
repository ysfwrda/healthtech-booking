package com.healthtech.appointment.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Relay delay pushed out to an hour so the live scheduler does not claim the seeded rows
// and try to send them to an unreachable broker.
@SpringBootTest(properties = {"outbox.pruning.retention-days=7", "outbox.relay.fixed-delay-ms=3600000"})
@Testcontainers
@DirtiesContext
class OutboxPruningJobTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Autowired
    OutboxRepository outboxRepository;
    @Autowired
    OutboxPruningJob outboxPruningJob;

    private OutboxMessage row(LocalDateTime publishedAt) {
        return OutboxMessage.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID().toString())
                .topic("pruning-test")
                .payload("{}")
                .publishedAt(publishedAt)
                .build();
    }

    @Test
    void prune_deletesOnlyPublishedRowsPastRetention() {
        // Arrange
        OutboxMessage publishedOld = outboxRepository.save(row(LocalDateTime.now().minusDays(10)));
        OutboxMessage publishedRecent = outboxRepository.save(row(LocalDateTime.now().minusDays(1)));
        OutboxMessage unpublishedOld = outboxRepository.save(row(null));

        // Act
        outboxPruningJob.prune();

        // Assert
        assertThat(outboxRepository.findById(publishedOld.getId())).isEmpty();
        assertThat(outboxRepository.findById(publishedRecent.getId())).isPresent();
        assertThat(outboxRepository.findById(unpublishedOld.getId())).isPresent();
    }
}
