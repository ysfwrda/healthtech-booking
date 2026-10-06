package com.healthtech.patient.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthtech.patient.filter.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxEventWriterTest {

    record SampleEvent(String name) {}

    @Mock
    private OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearMdc() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    private OutboxMessage writeAndCapture(OutboxEventWriter writer, String topic, UUID aggregateId, UUID eventId) {
        writer.write(topic, aggregateId, eventId, new SampleEvent("hello"));
        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void write_shouldSaveRowWithIdsTopicAndJsonPayload() {
        // Arrange
        UUID aggregateId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        // Act
        OutboxMessage row = writeAndCapture(new OutboxEventWriter(outboxRepository, objectMapper),
                "some.topic", aggregateId, eventId);

        // Assert
        assertThat(row.getId()).isEqualTo(eventId);
        assertThat(row.getAggregateId()).isEqualTo(aggregateId.toString());
        assertThat(row.getTopic()).isEqualTo("some.topic");
        assertThat(row.getPayload()).isEqualTo("{\"name\":\"hello\"}");
        assertThat(row.getPublishedAt()).isNull();
    }

    @Test
    void write_withCorrelationIdInMdc_shouldCarryItOnTheRow() {
        // Arrange
        MDC.put(CorrelationIdFilter.MDC_KEY, "request-123");

        // Act
        OutboxMessage row = writeAndCapture(new OutboxEventWriter(outboxRepository, objectMapper),
                "some.topic", UUID.randomUUID(), UUID.randomUUID());

        // Assert
        assertThat(row.getCorrelationId()).isEqualTo("request-123");
    }

    @Test
    void write_withoutCorrelationIdInMdc_shouldGenerateOne() {
        // Arrange: no correlation id in the MDC

        // Act
        OutboxMessage row = writeAndCapture(new OutboxEventWriter(outboxRepository, objectMapper),
                "some.topic", UUID.randomUUID(), UUID.randomUUID());

        // Assert
        assertThat(row.getCorrelationId()).isNotBlank();
        assertThat(UUID.fromString(row.getCorrelationId())).isNotNull();
    }

    @Test
    void write_serializationFailure_shouldThrowIllegalStateAndSaveNothing() throws Exception {
        // Arrange
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
        OutboxEventWriter writer = new OutboxEventWriter(outboxRepository, failingMapper);

        // Act and Assert
        assertThatThrownBy(() -> writer.write("some.topic", UUID.randomUUID(), UUID.randomUUID(), new SampleEvent("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to serialize SampleEvent event");
        verify(outboxRepository, never()).save(any());
    }
}
