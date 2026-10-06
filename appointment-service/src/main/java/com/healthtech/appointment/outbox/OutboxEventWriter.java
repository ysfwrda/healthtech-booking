package com.healthtech.appointment.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.appointment.filter.CorrelationIdFilter;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Writes a domain event as an outbox row. Called from inside the business method's
// @Transactional boundary, so the row commits or rolls back together with the state change
// it describes; OutboxRelay publishes it to Kafka afterwards (see ADR-008).
@Component
@RequiredArgsConstructor
public class OutboxEventWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public void write(String topic, UUID aggregateId, UUID eventId, Object event) {
        outboxRepository.save(OutboxMessage.builder()
                .id(eventId)
                .aggregateId(aggregateId.toString())
                .topic(topic)
                .payload(serialize(event))
                .correlationId(correlationIdOrGenerate())
                .build());
    }

    // Threads the current request's correlation id onto the outgoing Kafka message so a
    // consumer processing this event can tie its own log lines back to the request that
    // produced it. Falls back to a fresh id outside a request context (e.g. a test),
    // matching CorrelationIdFilter's own fallback for a missing incoming header.
    private String correlationIdOrGenerate() {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return correlationId != null ? correlationId : UUID.randomUUID().toString();
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize " + event.getClass().getSimpleName() + " event", ex);
        }
    }
}
