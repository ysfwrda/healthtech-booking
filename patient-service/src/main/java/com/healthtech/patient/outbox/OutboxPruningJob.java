package com.healthtech.patient.outbox;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// Cron rather than fixed delay, so this does not fire on every restart. Unpublished rows are
// never deleted regardless of age (see the repository query).
@Component
@RequiredArgsConstructor
public class OutboxPruningJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxPruningJob.class);

    private final OutboxRepository outboxRepository;

    @Value("${outbox.pruning.retention-days:7}")
    private int retentionDays;

    @Scheduled(cron = "${outbox.pruning.cron:0 0 3 * * *}")
    @Transactional
    public void prune() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = outboxRepository.deleteByPublishedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Pruned {} published outbox rows older than {}", deleted, cutoff);
        }
    }
}
