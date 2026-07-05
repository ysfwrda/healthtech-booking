# ADR-006: Static Service Discovery

## Status

Accepted

## Context

In a microservice system, services need a way to locate one another and the
API Gateway needs to know where to route inbound requests. Two broad approaches
were considered:

Static discovery: addresses are configured ahead of time (configuration files,
environment variables, fixed hostnames). Simple, with no additional
infrastructure, but it assumes a known, stable topology and a single instance
per service.

Dynamic discovery: a registry such as Eureka or Consul, where services register
themselves on startup and callers query the registry to resolve addresses. This
handles services moving, scaling to multiple instances behind one logical name,
and instances failing and deregistering. It is more robust, but it is additional
infrastructure to run, configure, and operate.

The relevant question is not which is more capable in general, but how much
service location this system actually needs, given decisions already made in
ADR-002 (Kafka as event broker) and ADR-005 (cross-service validation via an
event-driven read-model).

## Decision

The system uses static service discovery. Dynamic discovery (Eureka, Consul) is
deferred.

This is sufficient because the design largely removes the need for services to
locate one another directly:

Service-to-service communication is asynchronous via Kafka. When one service
needs to inform another (for example patient.registered, doctor.registered,
appointment.booked), it publishes an event rather than calling the other
service. Producers and consumers rendezvous through the Kafka broker, so no
service needs to resolve another service's network address for these flows.

Cross-service validation is local, not synchronous. Per ADR-005, Appointment
Service validates that a patient and doctor exist by checking its own read-model,
which is populated from events, rather than by calling Patient Service or Doctor
Service. This removes the one place a service would otherwise need to discover
and call another service at request time.

The only address-dependent component is the API Gateway, which routes a fixed
set of path prefixes to a known set of services. This is a small, stable mapping
and is configured statically in the gateway.

Because the async, event-driven design means services do not address each other
directly, and the gateway routes to a fixed topology, static configuration is
adequate and dynamic discovery would solve a problem the system does not yet
have.

## Consequences

### Positive

* No service discovery infrastructure to run, configure, or operate; the system
  stays simpler.
* The choice follows directly from the async, event-driven design: because
  services rendezvous through Kafka and validate against local read-models
  rather than calling each other, discovery is not required for correctness.
* The static topology is easy to reason about and to document; the gateway's
  routing table is explicit.

### Negative

* Static configuration assumes a single instance per service behind each address.
  It does not load-balance across multiple instances of the same service.
* The revisit trigger is horizontal scaling or a move to an orchestrator: running
  multiple instances of a service behind one logical name, or deploying to
  Kubernetes, would require dynamic resolution. At that point, dynamic discovery
  (Eureka or Consul) or platform-native service discovery (for example Kubernetes
  Services) becomes the appropriate evolution. This is documented as a known
  future improvement.
