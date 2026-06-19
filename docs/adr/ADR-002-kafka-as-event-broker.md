# ADR-002: Kafka as Event Broker over REST and Message Queuing

## Status

Accepted

## Context

In a microservice architecture, services need to communicate with each other. The Appointment Service must notify other services (e.g. Notification Service) when a booking or cancellation occurs.

Three approaches were evaluated: direct inter-service REST calls, message queuing (RabbitMQ), and event streaming (Kafka).

Direct REST calls introduce temporal coupling : both services must be available simultaneously, and a failure in one directly affects the other. RabbitMQ decouples services asynchronously but is designed for single-consumer processing, meaning messages are consumed once and cannot be replayed. Kafka's event streaming model addresses both limitations.

## Decision

We adopt Apache Kafka as the event broker, enabling asynchronous communication and temporal decoupling compared to direct REST calls, and supporting multiple independent consumers and event replay compared to RabbitMQ. The added operational complexity is consciously accepted as it aligns with the learning and demonstration goals of this project.

> **Note:** The current setup uses Zookeeper for Kafka cluster coordination, which is the most widely documented approach for local development. Since Kafka 3.3, KRaft mode (Kafka without Zookeeper) is production-ready and is a candidate for a future improvement.

## Consequences

**Positive**
- Stronger decoupling between services through event-driven architecture
- Multiple services can independently consume and replay the same event (e.g. after a failure or for debugging)
- Better fault isolation : if the Notification Service is temporarily unavailable, it resumes processing from where it left off without affecting the Appointment Service

**Negative**
- Added complexity for a small project : configuring brokers, producers, and consumers is overkill at this scale, consciously accepted for portfolio purposes
- Operational overhead for managing the broker and monitoring consumer group performance
- Eventual consistency : there is no guarantee that all consumers have processed an event at the same point in time, requiring careful design around consistency boundaries