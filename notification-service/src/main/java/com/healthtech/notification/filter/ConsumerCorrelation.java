package com.healthtech.notification.filter;

import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

// The Kafka-consumer counterpart of CorrelationIdFilter: puts the producer's correlation id
// into the MDC for the duration of one message, so every log line written while handling it
// carries the id, and clears it afterwards so it never leaks onto the next message.
public final class ConsumerCorrelation {

    private ConsumerCorrelation() {
    }

    public static void runWith(byte[] correlationIdHeader, Runnable work) {
        MDC.put(CorrelationIdFilter.MDC_KEY, resolveCorrelationId(correlationIdHeader));
        try {
            work.run();
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    // Correlation id is read from the Kafka header the producer attached, tying the
    // consumer's log lines back to the HTTP request that triggered the event. Falls back
    // to a fresh id if the header is absent (e.g. a message produced before propagation
    // existed, or one published outside a request context such as the demo seeder).
    private static String resolveCorrelationId(byte[] correlationIdHeader) {
        return correlationIdHeader != null
                ? new String(correlationIdHeader, StandardCharsets.UTF_8)
                : UUID.randomUUID().toString();
    }
}
