package com.healthtech.patient.correlation;

import org.slf4j.MDC;

import java.util.UUID;

// Where the correlation id lives while this service handles one request or message (ADR-007).
// CorrelationIdFilter (HTTP), ConsumerCorrelation (Kafka), the outbox writer and the relay all
// use these names, so none of them has to depend on the web filter to find them.
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    // The id of the request or message being handled, or a fresh one outside any such context
    // (e.g. a test or a scheduled job), matching CorrelationIdFilter's fallback for a missing header.
    public static String currentOrNew() {
        String correlationId = MDC.get(MDC_KEY);
        return correlationId != null ? correlationId : UUID.randomUUID().toString();
    }
}
