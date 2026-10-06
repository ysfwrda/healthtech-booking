package com.healthtech.notification.filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsumerCorrelationTest {

    @AfterEach
    void clearMdc() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Test
    void runWith_header_shouldExposeItInMdcDuringWorkAndClearAfter() {
        // Arrange
        AtomicReference<String> seen = new AtomicReference<>();

        // Act
        ConsumerCorrelation.runWith("request-123".getBytes(StandardCharsets.UTF_8),
                () -> seen.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        // Assert
        assertThat(seen.get()).isEqualTo("request-123");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void runWith_noHeader_shouldGenerateAFreshId() {
        // Arrange
        AtomicReference<String> seen = new AtomicReference<>();

        // Act
        ConsumerCorrelation.runWith(null, () -> seen.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        // Assert
        assertThat(seen.get()).isNotBlank();
        assertThat(UUID.fromString(seen.get())).isNotNull();
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void runWith_workThrows_shouldRethrowAndStillClearMdc() {
        // Arrange
        RuntimeException failure = new RuntimeException("boom");

        // Act and Assert
        assertThatThrownBy(() -> ConsumerCorrelation.runWith(
                "request-123".getBytes(StandardCharsets.UTF_8), () -> { throw failure; }))
                .isSameAs(failure);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
