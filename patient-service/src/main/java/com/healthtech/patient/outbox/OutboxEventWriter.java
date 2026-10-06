package com.healthtech.patient.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.patient.correlation.CorrelationId;
import com.healthtech.patient.event.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Writes a domain event as an outbox row. Called from inside the business method's
// @Transactional boundary, so the row commits or rolls back together with the state change
// it describes; OutboxRelay publishes it to Kafka afterwards (see ADR-008).
@Component
@RequiredArgsConstructor
public class OutboxEventWriter implements DomainEventPublisher {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    // MANDATORY: throws if there is no surrounding transaction, instead of saving the row in its own
    // and losing atomicity with the state change. Does not start a transaction itself.
    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void publish(String topic, UUID aggregateId, UUID eventId, Object event) {
        outboxRepository.save(OutboxMessage.builder()
                .id(eventId)
                .aggregateId(aggregateId.toString())
                .topic(topic)
                .payload(serialize(event))
                .correlationId(CorrelationId.currentOrNew())
                .build());
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize " + event.getClass().getSimpleName() + " event", ex);
        }
    }
}
