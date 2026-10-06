package com.healthtech.notification.correlation;

// Where the correlation id lives while this service handles one request or message (ADR-007).
// CorrelationIdFilter (HTTP) and ConsumerCorrelation (Kafka) both use these names, so the
// consumers never have to depend on the web filter to find them.
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }
}
