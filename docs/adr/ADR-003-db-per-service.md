# ADR-003: Database per Service Pattern

## Status

Accepted

## Context

As established in ADR-001, loose coupling and independent scalability are the core reasons for choosing a microservice architecture. A shared database directly undermines both goals.

In a shared database setup, services can directly read or modify each other's tables, introducing schema coupling. A change in the Appointment Service schema can silently break the Notification Service, making independent development and deployment impossible. This is the anti-pattern that the Database per Service pattern is designed to avoid.

Each service therefore owns its own database, and data exchange between services happens exclusively via Kafka events, as established in ADR-002.

## Decision

We adopt the Database per Service pattern, giving each service its own dedicated PostgreSQL instance. This enforces loose coupling at the data layer and enables independent scalability, the Notification Service can be scaled independently of the Appointment Service based on its own load characteristics.

## Consequences

**Positive**
- Loose coupling at the data layer : no service can access another service's database directly
- Independent scalability : each service's database can be scaled, tuned, or even replaced based on that service's specific needs
- Independent deployability : schema changes in one service do not affect other services

**Negative**
- Higher complexity : managing multiple database instances adds operational overhead compared to a single shared database
- Data consistency is harder to achieve : there is no shared transaction across services, requiring eventual consistency patterns
- Distributed transactions are not possible : ACID guarantees apply only within a single service boundary. Cross-service requests need to be handled using patterns such as the Saga pattern
- Replication and synchronization between services must be handled explicitly via events, adding design overhead

