# ADR-009: Consumer Idempotency and Failure Handling

## Status

Proposed

## Context

ADR-008 adopts the transactional outbox, which gives at-least-once delivery: the relay can publish
and then fail before setting `published_at`, and the next poll republishes the same event. Kafka
itself is also at-least-once, and a consumer rebalance can redeliver. ADR-008 names this ADR as
the specification of the consumer-side work that decision depends on. It ships as the PR after
the outbox, not with it.

Two consumer groups are affected:

* **notification-service** consumes `appointment.booked` and `appointment.cancelled` and persists
  a `Notification`. `Notification` does not store `eventId` and has no uniqueness constraint, so
  a redelivered event creates a second row and, once an external channel exists, a second message
  to the patient.
* **appointment-service read models** consume `patient.registered` and `doctor.registered` and
  upsert by key. These are already idempotent.

Failure handling is also undefined. A message that cannot be deserialized or processed has no
defined outcome, and a poison pill can block a partition indefinitely. The outbox makes this
worse, because it will keep redelivering whatever it was handed.

Three delivery guarantees were considered.

At-most-once: commit offsets before processing. Rejected. Loss is silent, nothing signals that an
appointment confirmation never happened, and the duplication it avoids is preventable.

Exactly-once through Kafka transactions: rejected. It covers Kafka-to-Kafka flows, not a consumer
that writes to Postgres, so it would not close the gap here.

At-least-once with idempotent consumers: duplicates are tolerated and made harmless at the
consumer. This is the only option where a failure is both visible and recoverable.

## Decision

**Delivery.** At-least-once everywhere, with consumers made idempotent.

**Duplicate detection key.** `eventId`. It is stable across redelivery, and per ADR-008 it is the
outbox row id and travels in the payload, so one UUID identifies the event end to end. It is not
the Kafka record key or a header; consumers read it from the payload. Events published by
`DoctorSeeder`, which bypasses the outbox (ADR-008, Scope), carry a random `eventId` with no
outbox row, which is harmless because the read model is a keyed upsert. Business
keys such as appointment id are not used: the same appointment legitimately produces both a
booked and a cancelled event.

**Enforcement.** A unique index on `event_id` in the notification table. The `Notification` entity has no
explicit `@Table` today, so the table name must be pinned when this is implemented. The database decides
whether an event was already processed. Two alternatives were rejected:

* Check-then-insert (`existsByEventId`, then save). It has a race: during a rebalance two
  consumers can both see "absent" and both insert. The check gives false confidence and still
  needs the constraint.
* `SERIALIZABLE` isolation. It does not remove the conflict, it moves it into serialization
  failures that then need retry logic, which is more machinery than a unique index.

**Duplicate handling.** The consumer persists with `saveAndFlush`, so the constraint is checked at
the call rather than at a later commit. It catches only the specific unique-constraint violation
on `event_id`, not `DataIntegrityViolationException` in general, because other integrity failures
are real errors and must not be swallowed. A duplicate is logged at DEBUG or INFO, not WARN or
ERROR, since it is expected behavior. The catch sits outside the transaction boundary: a
constraint violation marks the transaction rollback-only, so catching inside it would leave a
poisoned transaction. This is tested against real Postgres, not an in-memory substitute, because
constraint and flush behavior differ.

**Read models.** No code change. The `patient.registered` and `doctor.registered` consumers are
idempotent by keyed upsert. This is proven by redelivery tests rather than assumed, including that
redelivering `DoctorRegistered` leaves `openingHours` unchanged and not duplicated.

**Poison pills.** Consumers wrap their deserializers in `ErrorHandlingDeserializer`. Without it a
deserialization failure is thrown inside the Kafka client before the container's error handler
runs, so the error handler never sees it and the consumer loops on the same record. With it, the
failure is delivered to the error handler as a normal processing failure.

**Retry classification.** Failures are classified rather than retried uniformly:

* Permanent failures (deserialization errors, validation failures, malformed payloads) go straight
  to the dead-letter topic. Retrying cannot succeed.
* Transient failures (database unavailable, timeouts) retry with backoff, then go to the
  dead-letter topic if retries are exhausted.

**Retry strategy.** Blocking retry for both consumer types. The total backoff is kept well below
`max.poll.interval.ms`, so retrying never causes the consumer to be evicted from the group and
trigger a rebalance. Non-blocking retry topics were rejected for now: they add topics, headers
and reordering for a consumer whose only side effect is a database insert. This is revisited for
notification-service when it gains an external channel (email or SMS), where a slow third-party
call makes blocking retry hold up the partition.

**Dead-letter topic.**

* Every publish to the DLT is logged at ERROR, so a dead letter is an alertable event rather than
  a silent one.
* Replay is a documented manual procedure. Nothing replays automatically, because a permanent
  failure replayed unchanged fails again.
* Retention is 3 days, with `segment.ms` set to match. Kafka deletes whole segments only, so with
  the default 7-day `segment.ms` the active segment would hold records well past the retention
  setting.

**Relay `attempts` column.** Not added to the outbox table. The relay retries forever. A poison
row is detected through oldest-unpublished age, the signal ADR-008 already identifies, rather
than a retry counter that would need its own handling policy and its own parked state.

## Consequences

### Positive

* Duplicate relay delivery and consumer rebalance redelivery no longer produce duplicate
  notifications. The guarantee lives in the database and holds across consumer instances.
* A poison pill no longer blocks a partition. It is dead-lettered once, loudly.
* Permanent failures stop wasting retries, and transient failures recover without intervention.
* The read-model consumers needed no change, and redelivery tests now pin that property.

### Negative

* The unique index adds a write-path cost on every notification insert, and existing rows need
  `event_id` populated or the column added nullable before the constraint can be enforced on
  historical data.
* Blocking retry holds a partition during backoff. Acceptable now, and the reason for the
  revisit trigger once an external channel exists.
* DLT replay is manual. Someone has to notice the ERROR log and act, and there is no tooling.
* The DLT's 3-day retention is a deadline: a dead letter not investigated within it is lost.
* Retry classification is a maintained list of exception types, and misclassifying one is a
  silent bug in either direction.

### Known limits

* **No per-aggregate ordering across topics.** Message keys order events within a topic, but
  `appointment.booked` and `appointment.cancelled` are separate topics and can be consumed in
  either order. A cancellation can arrive before its booking.
* **Idempotency does not protect against stale updates.** Deduplication by `eventId` stops the
  same event being applied twice. It does not stop an older event overwriting newer state. That is
  harmless while events are create-only, and becomes a real problem the day update events exist,
  when a version or sequence check will be required.
* **Event payloads may carry more personal data than consumers need.** The payloads, and the
  outbox and DLT copies of them, are patient data held for retention periods nobody chose on
  data-minimization grounds. Payload contents should be reviewed against what each consumer
  uses.
