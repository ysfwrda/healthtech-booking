package com.healthtech.doctor.outbox;

import com.healthtech.doctor.event.DoctorRegistered;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// No Kafka container here (Postgres only), so DoctorSeeder's own KafkaTemplate is mocked: without
// it, its startup send would block for the producer's default max.block.ms against an
// unreachable broker (see DoctorIntegrationTest for the same, established reason).
@SpringBootTest(properties = "outbox.pruning.retention-days=7")
@Testcontainers
@DirtiesContext
class OutboxPruningJobTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    @MockitoBean
    KafkaTemplate<String, DoctorRegistered> kafkaTemplate;

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
        OutboxMessage publishedOld = outboxRepository.save(row(LocalDateTime.now().minusDays(10)));
        OutboxMessage publishedRecent = outboxRepository.save(row(LocalDateTime.now().minusDays(1)));
        OutboxMessage unpublishedOld = outboxRepository.save(row(null));

        outboxPruningJob.prune();

        assertThat(outboxRepository.findById(publishedOld.getId())).isEmpty();
        assertThat(outboxRepository.findById(publishedRecent.getId())).isPresent();
        assertThat(outboxRepository.findById(unpublishedOld.getId())).isPresent();
    }
}
