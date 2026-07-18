# ADR-007: Correlation ID Propagation for Log Correlation

## Status

Accepted

## Context

In a system split across five services, a single logical operation produces log
lines in more than one place. Reconstructing what happened for one request means
relating those lines to each other. Without a shared identifier, the only tools
are timestamps and entity identifiers, which is slow and unreliable once more
than one operation is in flight.

Three approaches were considered:

No correlation mechanism: reconstruct operations by grepping for an entity id
(a patientId, an appointmentId) and comparing timestamps across services. Free,
but it fails exactly when it is needed most, under concurrent activity, and it
cannot relate log lines that do not happen to mention the same entity.

Correlation ID via MDC: generate an identifier at the system edge, carry it
through the logging context, and include it in the log pattern. Every line
belonging to one logical operation carries the same id, so a single grep
reconstructs it. No new infrastructure, but propagation across process
boundaries must be wired by hand.

Distributed tracing: OpenTelemetry with a collector and a backend such as Zipkin
or Jaeger, or Micrometer Tracing. This produces spans with parent/child
relationships, automatic timing, and a visualization of where a request spent
its time. Propagation is instrumented automatically rather than written by hand.
It requires running and operating a collector and a trace backend.

The relevant question is which boundaries actually need to be crossed in this
system. Per ADR-005, cross-service validation is resolved against a local
read-model populated from events rather than by calling other services
synchronously. Combined with ADR-002, this means there are no synchronous
service-to-service HTTP calls: the only HTTP hop is the gateway to a single
service, and everything genuinely distributed happens asynchronously over Kafka.

That inverts the usual priority. The multi-hop HTTP call chain that tracing
libraries are built to instrument does not exist here. The boundary that matters
is the asynchronous one, where a request publishes an event and a different
service consumes it later, on a different thread, in a different process.

## Decision

The system uses correlation IDs carried in the logging context (MDC) and
propagated across both boundaries it has. Distributed tracing is deferred.

At the HTTP boundary, each service runs a `CorrelationIdFilter` that reads an
inbound `X-Correlation-Id` header or generates one when absent, places it in MDC,
and echoes it on the response. The gateway resolves or generates the id first and
overwrites the outbound header before proxying, so the id is established once at
the edge rather than being reinvented downstream. The log pattern includes
`%X{correlationId}`, so every line emitted during the request carries it.

At the Kafka boundary, producers attach the current MDC correlation id to the
message as an `X-Correlation-Id` header, and consumers read that header back into
their own MDC before processing. This is the part that makes the mechanism useful
in this architecture. Without it the id stops at the HTTP boundary: a consumer
would generate an unrelated id, and a registration request and the read-model
projection it triggered would appear in the logs as two unconnected operations,
even though the projection is the entire point of the request under ADR-005.

Both sides degrade rather than fail when the id is absent. A producer publishing
outside a request context, such as the demo data seeder, has no id in MDC and
generates one. A consumer receiving a message with no header, whether published
before this mechanism existed or from such a producer, generates one rather than
rejecting the message. This mirrors the filter's own behaviour for a missing
inbound header.

Tracing is deferred on cost and stage, not because it would add nothing. It would
add real value here: OpenTelemetry propagates context through Kafka headers as
well as HTTP, so it would cover the same asynchronous boundary, and it would
instrument that propagation automatically rather than leaving it as a convention
each new producer and consumer has to follow. What it also requires is a
collector and a trace backend to run and operate, for a system that currently has
no production traffic whose latency breakdown is being investigated and no deep
synchronous call chains whose timing needs visualizing. At this stage the
correlation id delivers most of the debugging value at none of the operational
cost, so this is a decision about when rather than whether. Distributed tracing
is planned as Phase 3 work; the trigger is stated under Consequences.

## Consequences

### Positive

* No new infrastructure. There is no collector, no trace backend, and no vendor
  lock-in; the mechanism is a servlet filter, a Kafka header, and a log pattern.
* A single grep for one id across services reconstructs a logical operation
  end to end, including the asynchronous hop from a registration request to the
  read-model projection it produced.
* The mechanism covers the boundary this architecture actually depends on.
  Because ADR-005 makes the async read-model the cross-service correctness
  mechanism, the Kafka hop is where failures hide, and it is now traceable.
* Absent headers degrade to a generated id rather than an error, so events
  published outside a request context and messages produced before this change
  are still consumed and still logged coherently within their own processing.

### Negative

* This is log correlation, not tracing. There are no spans, no parent/child
  relationships, no automatic timing, and no visualization. Answering "how long
  between publish and projection" means comparing timestamps by hand.
* Propagation is manual on both sides. Every new producer must attach the header
  and every new consumer must read it. A tracing library instruments this
  automatically; here it is a convention that can be forgotten when a new
  producer or consumer is added, and nothing fails loudly when it is.
* The correlation id is only as useful as the log lines that carry it, and logs
  are not aggregated. Reconstructing an operation means grepping across
  containers rather than querying one place. Centralized log aggregation is a
  separate concern and is not addressed here.
* The revisit trigger is production traffic or horizontal scaling: when latency
  breakdown, sampling, or span-level visualization is actually needed, or when
  multiple instances per service make manual log correlation impractical,
  OpenTelemetry becomes the appropriate evolution. The MDC id maps naturally onto
  a trace id at that point, so this is a step toward tracing rather than a
  detour. This is documented as a known future improvement.
