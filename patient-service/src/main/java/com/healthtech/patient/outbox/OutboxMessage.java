package com.healthtech.patient.outbox;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxMessage {

    // Caller assigns this explicitly from the domain event's own eventId, so one UUID
    // identifies the outbox row, the Kafka record and the consumer's dedup key.
    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(nullable = false, length = 128)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;
}
