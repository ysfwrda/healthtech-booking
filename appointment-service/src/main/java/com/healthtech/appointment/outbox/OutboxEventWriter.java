package com.healthtech.appointment.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.correlation.CorrelationId;
import com.healthtech.appointment.event.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Writes a domain event as an outbox row. Called from inside the business method's
// @Transactional boundary, so the row commits or rolls back together with the state change
// it describes; OutboxRelay publishes it to Kafka afterwards (see ADR-008).
@Component
@RequiredArgsConstructor
public class OutboxEventWriter implements DomainEventPublisher {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

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
