package com.healthtech.patient.event;

import java.util.UUID;

// What business code needs in order to announce that something happened. Services depend on
// this, not on how events leave the service: today OutboxEventWriter stores them in the
// transactional outbox (ADR-008), and switching that mechanism needs no change to a service.
public interface DomainEventPublisher {

    // Must be called inside the caller's transaction, so the event is only published if the
    // state change it describes commits.
    void publish(String topic, UUID aggregateId, UUID eventId, Object event);
}
