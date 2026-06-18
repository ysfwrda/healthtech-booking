# ADR-001: Microservices Architecture over Monolith

## Status

Proposed

## Context

We are building a platform connecting healthcare providers with patients to enable online appointment management and AI-Assisted triage to ensure efficient treatment of patients.

The project is mainly for demonstration purposes, demonstrating skills in distributed systems architecture, with key requirements of scalability across regions, low latency, high throughput, and technology flexibility.

The first architectural decision shapes everything downstream — service boundaries, data ownership, deployment strategy, and inter-service communication patterns — which is why it needs to be made and documented first.

Choices available for high level design are Microservices and Monolith, where Monoliths are normally the best choice for small teams and at early stages of application development.

## Decision

We adopt Microservices Architecture, leveraging independent scalability per service, fault isolation, and technology flexibility — for example, using Python for the AI-Assisted Triage service. While a Monolith would be the conventional choice for an early-stage project, Microservices are intentionally chosen to demonstrate distributed systems knowledge and build hands-on experience with this architecture.

## Consequences

**Positive**
- Independent scalability per service based on load
- Fault isolation — failure in one service does not cascade to others
- Technology flexibility — different stacks can be used per service
- High availability through independent deployment

**Negative**
- Higher architectural complexity due to inter-service communication overhead
- Independent databases per service add complexity for requests spanning multiple services
- Slower development cycles compared to a Monolith