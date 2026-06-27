# ADR-005: Cross-Service Validation Strategy for Booking

## Status

Accepted

## Context

Before creating an appointment, the Appointment Service must confirm that the
referenced patient and doctor actually exist. This is a precondition check: a
question that must be answered before the booking is persisted.

ADR-002 established Kafka and asynchronous events as the communication mechanism
for notification between services, deliberately avoiding the temporal coupling
of direct REST calls. That decision addressed notification (announcing that
something has happened). Validation is a different interaction shape, a query
that requires an answer before proceeding, which ADR-002 did not cover.

Two approaches were evaluated.

Synchronous REST validation: the Appointment Service calls the Patient Service
and Doctor Service at booking time to confirm existence. This reintroduces the
temporal coupling ADR-002 removed (a booking fails if either service is
unavailable) and makes runtime service discovery a hard requirement.

Asynchronous read-model validation: the Appointment Service consumes
PatientRegistered and DoctorRegistered events and maintains a local store of
valid patient and doctor identifiers. At booking time it validates against its
own local data, with no runtime call to other services.

## Decision

We adopt the asynchronous read-model approach. The Appointment Service consumes
PatientRegistered and DoctorRegistered events and maintains a local read-model
of valid patient and doctor identifiers. Booking validation checks this local
read-model rather than calling other services synchronously.

This choice is justified on three grounds:

1. It preserves the decoupling established in ADR-002. The Appointment Service
   remains self-sufficient at booking time and does not depend on the Patient
   or Doctor Service being available.

2. The eventual-consistency window is not a practical concern for this domain.
   Event propagation takes milliseconds to seconds, while the real user flow
   between registration and booking (finding a doctor, selecting a slot,
   entering details) spans considerably longer, so the read-model is reliably
   current by the time a booking request arrives.

3. The event infrastructure already exists. Consuming events into a local
   read-model is a natural extension of the established Kafka patterns rather
   than added architectural complexity.

When a booking references an identifier not present in the read-model, the
booking is rejected with a client-facing error. No inline retry is performed.
The rare case of an identifier that exists but has not yet propagated is treated
as a negligible, client-retryable failure rather than a case to engineer around,
consistent with the consistency analysis above.

## Consequences

**Positive**
- The Appointment Service has no runtime dependency on the Patient or Doctor
  Service for validation, preserving availability and fault isolation
- Consistent with the event-driven architecture of ADR-002 rather than carving
  out a synchronous exception
- Demonstrates the event-carried state transfer pattern: each service holds the
  data it needs locally, kept current via events
- Reduces the importance of runtime service discovery (see ADR-006), since there
  are no synchronous inter-service calls at booking time

**Negative**
- More implementation effort than synchronous calls: a Kafka consumer, a local
  read-model store, and associated tests are required in the Appointment Service,
  consciously accepted for the decoupling and demonstration value
- Eventual consistency means the read-model can briefly lag reality; a booking
  for a just-registered patient could be rejected in the rare propagation window,
  accepted as a client-retryable edge case
- The read-model duplicates identifier data already owned by the Patient and
  Doctor Services, a deliberate tradeoff inherent to the pattern

> **Note:** Resilience concerns for the event consumers themselves (retry on
> transient processing failure, dead-letter handling, idempotency) are out of
> scope here and are addressed as part of the Phase 3 reliability work.