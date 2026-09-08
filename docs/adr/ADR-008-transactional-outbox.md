# ADR-008: Transactional Outbox for Domain Event Publishing

## Status

Proposed

## Context

The system is event-driven: services communicate asynchronously over Kafka rather than by
synchronous calls (see ADR-002: Kafka as Event Broker). Four code paths currently publish
domain events, and all four write to the database and to Kafka as two independent operations:

* `AuthService.register` (patient-service): persists the patient, then publishes
  `PatientRegistered` to `patient.registered` for the appointment-service read model.
* `DoctorAuthService.register` (doctor-service): persists the doctor, then publishes
  `DoctorRegistered` to `doctor.registered` for the same read model. This method is annotated
  `@Transactional` and the publish happens inside the transaction boundary.
* `AppointmentService.bookAppointment`: persists the appointment, then publishes
  `AppointmentBooked` to `appointment.booked` for notification-service.
* `AppointmentService.cancelAppointment`: updates the appointment status, then publishes
  `AppointmentCancelled` to `appointment.cancelled` for notification-service.

Postgres and Kafka are separate systems with no shared transaction, so these two writes cannot
succeed or fail together. This is the dual-write problem, and it appears here in two directions.

Where there is no transaction, the database commit happens first. If the publish then fails, the
row is committed and the event is lost. A cold producer fails fast on metadata and surfaces a
500 to the caller, which is at least visible. A warm producer accepts the record into its send
buffer, returns immediately, and fails asynchronously after the 201 has already been returned.
The second case is the dangerous one: the caller is told the operation succeeded and nothing
downstream ever learns about it.

Where the method is `@Transactional`, as in `DoctorAuthService.register`, the annotation does not
make the two writes atomic. It inverts the failure mode. The record is handed to the producer
before commit, so a rollback leaves a published event for a doctor that was never persisted.

This is not theoretical. It was reproduced during development: with Kafka down, patient
registration returned 500 while the patient row was already committed to `patient_db`. The
`PatientRegistered` event was never published, so the appointment-service read model never
learned about the patient, every subsequent booking for that patient returned 404 indefinitely,
and re-registering returned 409. The account was permanently unusable with no error visible
after the initial request.

Three approaches were considered.

Publish before persisting: emit the event first and write to the database afterwards. This does
not remove the dual write, it moves it. A successful publish followed by a failed database write
leaves consumers acting on state that does not exist, which is the worse of the two directions.

`@Transactional` on the publishing methods: the intuitive fix, and the one a reader will propose.
It does not work, because the Kafka producer is not a transactional resource enrolled in the JDBC
transaction. There is nothing to roll back if the database write fails after the send, and
nothing to retry inside the transaction if the send fails. `DoctorAuthService.register` is the
existing proof of this in the repository.

Transactional outbox: write the event as a row in the same database, in the same local
transaction as the aggregate. Atomicity then comes from Postgres against a single resource. A
separate relay reads those rows and publishes them to Kafka. The cost is a new table, a relay,
and a retention job.

## Decision

The transactional outbox pattern is adopted in the three publishing services: `patient-service`,
`doctor-service` and `appointment-service`.

**Atomicity.** The outbox row is inserted in the same local transaction as the aggregate write.
Either both commit or neither does. No compensating action is required on rollback and there is
no window in which an outbox row exists for an aggregate that does not. No Kafka call occurs
inside the transaction; `DoctorAuthService.register` has its existing in-transaction
`kafkaTemplate.send` removed rather than supplemented.

**Polling relay over CDC.** A scheduled relay polls the outbox table and publishes to Kafka. The
alternative is change data capture with Debezium reading the Postgres write-ahead log. CDC is
rejected at this stage for three reasons. It requires `wal_level=logical`, a replication user,
and a persistent replication slot per database; a slot holds WAL until its consumer confirms, so
a stopped connector accumulates WAL until the disk fills and Postgres refuses writes. That is
three slots and a real outage mode on a single 30GB volume with no metrics wired up yet. It also
adds a Kafka Connect process to a host already running five services, three Postgres instances
and Kafka. And there is no latency requirement that justifies either cost: the consumers write a
notification row and a read-model row, where a delay of one poll interval is invisible. The
outbox table schema is identical under both approaches, so this is a when, not a whether: moving
to CDC later replaces the relay and touches no producer code.

**Concurrent relays.** The claim query uses `FOR UPDATE SKIP LOCKED`:

```sql
SELECT *
FROM outbox
WHERE published_at IS NULL
ORDER BY created_at LIMIT 100
FOR
UPDATE SKIP LOCKED;
```

`FOR UPDATE` alone would make a second relay block until the first commits, and because the
transaction spans a network publish, that blocking would be long. `SKIP LOCKED` makes the second
relay skip locked rows and claim the next unlocked ones instead, so concurrent relays process
disjoint batches without duplicating work. This is free insurance rather than a present need: a
single service instance with Spring's single-threaded scheduler cannot overlap with itself. It
becomes load-bearing on horizontal scaling and during rolling deployments, when a draining
container and a starting container poll the same table.

**Schema.** One `outbox` table per publishing service, in that service's own database:

```sql
CREATE TABLE outbox
(
    id             UUID PRIMARY KEY,
    aggregate_id   VARCHAR(64)  NOT NULL,
    topic          VARCHAR(128) NOT NULL,
    payload        JSONB        NOT NULL,
    correlation_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_pending
    ON outbox (created_at) WHERE published_at IS NULL;
```

The index predicate is not optional. Without it the relay scans an index that grows without
bound as published rows accumulate.

`id` is the event's existing `eventId` rather than a separate identifier, so one UUID identifies
the outbox row, the Kafka record and the consumer's deduplication key.

`aggregate_id` is used as the Kafka message key, so all events for one patient, doctor or
appointment land on the same partition.

`correlation_id` is persisted because the relay publishes on a scheduler thread with no request
context. The relay re-attaches `X-Correlation-Id` as a Kafka header from this column, preserving
the propagation guarantee of ADR-007 across the new asynchronous hop.

**Delivery semantics.** This produces at-least-once delivery. The relay can publish successfully
and then fail before setting `published_at`, in which case the next poll republishes the same
event. This is inherent to the pattern and is accepted rather than solved.

Consumer idempotency is therefore a dependency of this decision, not optional follow-up work.
Duplicates are already possible today because Kafka itself is at-least-once and a consumer
rebalance can redeliver; the outbox raises their probability materially. The read-model consumers
are already idempotent by keyed upsert, but `Notification` does not persist `eventId` and has no
uniqueness constraint, so duplicate relay delivery would send duplicate notifications to
patients. The specification is ADR-009, and it ships together with the relay rather than after
it.

**Scope.** `DoctorSeeder` is excluded. It is a bootstrap path rather than a request path, and any
events it fails to publish can be regenerated by re-running the seeder.

**Retention.** Published rows are pruned daily, retaining seven days. A daily job against a
weekly retention keeps the maximum age bounded at eight days; a weekly job would allow fourteen.
Pruning is part of this decision rather than an operational detail, because an unbounded outbox
table is a foreseeable production failure.

## Consequences

### Positive

* The dual write is eliminated. A Kafka outage or a process crash can no longer produce a
  committed aggregate with no corresponding event, in either direction.
* A failed database write rolls back the outbox row with the aggregate, so no event is published
  for state that does not exist.
* Booking, patient registration and doctor registration no longer fail when Kafka is unavailable.
  Events accumulate in the outbox and drain when the broker returns.
* Unpublished row count and oldest unpublished age become direct health signals that can be
  alerted on, which did not exist before.
* The per-event-type `KafkaTemplate` beans in appointment-service are replaced by a single relay
  publisher, reducing configuration.

### Negative

* Two new components per service to operate and monitor: the relay and the pruning job.
* End-to-end delivery latency increases by up to one full poll interval.
* Every business write becomes two inserts.
* Duplicate delivery becomes a contract rather than an edge case. Every current and future
  consumer of these topics must be idempotent.
* Ordering weakens under concurrency. Message keys guarantee same-partition placement, but two
  relays can complete batches out of order, so per-aggregate ordering is not guaranteed across
  batch boundaries.
* Payloads are serialized at write time and may be relayed after a subsequent deployment, so
  event schemas require versioning.
* Publishing now depends on the database. This adds no new failure domain, since the database is
  already a hard dependency of every path that publishes, but it does mean the outbox cannot
  drain while Postgres is down.
* This makes the database write and the publish atomic within a single service. It does nothing
  for consistency across service boundaries, which remains the domain of sagas and is out of
  scope.
* The revisit trigger for the relay is production traffic or horizontal scaling: when polling
  load on the database or delivery latency becomes measurable, Debezium replaces the relay
  against the same table.