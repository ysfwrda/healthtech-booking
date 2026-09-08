package com.healthtech.doctor.outbox;

import com.healthtech.doctor.filter.CorrelationIdFilter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

// Runs on a scheduler thread with no request context, so it never reads MDC. Correlation ids
// are re-attached from the column persisted on each row (see ADR-008).
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> outboxKafkaTemplate;

    @Value("${outbox.relay.batch-size:100}")
    private int batchSize;

    public OutboxRelay(OutboxRepository outboxRepository,
                        @Qualifier("outboxKafkaTemplate") KafkaTemplate<String, String> outboxKafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.outboxKafkaTemplate = outboxKafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay-ms:1000}")
    public void poll() {
        relayBatch();
    }

    // The claim transaction holds FOR UPDATE SKIP LOCKED row locks across every publish in the
    // batch, so it stays open for the whole loop and commits (releasing the locks) only once
    // every row has been attempted. Rows are marked published individually as their own send
    // acknowledges, so one failing send does not prevent the rest of the batch from being marked.
    @Transactional
    public void relayBatch() {
        List<OutboxMessage> batch = outboxRepository.claimBatch(batchSize);
        for (OutboxMessage message : batch) {
            if (publish(message)) {
                message.setPublishedAt(LocalDateTime.now());
                outboxRepository.save(message);
            }
        }
    }

    private boolean publish(OutboxMessage message) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                message.getTopic(), message.getAggregateId(), message.getPayload());
        if (message.getCorrelationId() != null) {
            record.headers().add(CorrelationIdFilter.CORRELATION_ID_HEADER,
                    message.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        }
        try {
            outboxKafkaTemplate.send(record).get();
            return true;
        } catch (Exception ex) {
            log.warn("Failed to publish outbox row {} to topic {}", message.getId(), message.getTopic(), ex);
            return false;
        }
    }
}
