# HealthTech Appointment Booking Platform

A microservice-based appointment booking platform built as a **distributed systems and event-driven architecture project
**.

The focus is not only building services, but demonstrating how system behavior changes under different architectural
constraints: separating concerns early (service boundaries, async messaging, data ownership) and re-integrating them as
domain complexity grows (authentication, cross-service validation, business rules).

The work is organized in phases:

* **Phase 1:** Distributed system fundamentals (service boundaries, async communication, data ownership)
* **Phase 2:** Domain complexity (patients, doctors, authentication, availability, booking rules, cross-service
  validation)
* **Phase 3:** Production-grade concerns (observability, reliability, service discovery, AI-assisted workflows)

Phase 2 is substantially complete: end-to-end booking with JWT-secured, race-safe reservations and RFC 9457 error
handling.

---

## Tech Stack

Java 21 · Spring Boot 3 · Apache Kafka · PostgreSQL · Spring Cloud Gateway (MVC) · Spring Security (OAuth2 Resource
Server) · MapStruct · JWT (RS256) · Docker

---

## Architecture Overview (Phase 2)

```
        ┌─────────────┐          ┌───────────────────┐
        │   Client    │─────────▶│    API Gateway     │  (single entry, routing)
        └─────────────┘          └─────────┬─────────┘
                                           │
        ┌──────────────────────────────────┼──────────────────────────────────┐
        │                                  │                                  │
        ▼                                  ▼                                  ▼
┌─────────────────┐              ┌────────────────────┐              ┌────────────────┐
│ Patient Service │              │  Appointment Svc   │              │ Doctor Service │
│ auth, JWT issue │              │ booking, slots,    │              │ profiles,      │
│ (RS256)         │              │ read-model,        │              │ specialties,   │
│                 │              │ JWT validation     │              │ filtering      │
└────────┬────────┘              └─────────┬──────────┘              └───────┬────────┘
         │ patient.registered              │ appointment.booked              │ doctor.registered
         │                                 │ appointment.cancelled           │
         └─────────────────────────────────┼─────────────────────────────────┘
                                           ▼
                                   ┌────────────────┐
                                   │  Apache Kafka  │
                                   └───────┬────────┘
                                           │ appointment.booked / cancelled
                                           ▼
                                 ┌───────────────────┐
                                 │ Notification Svc  │
                                 └───────────────────┘
```

Appointment Service consumes `patient.registered` and `doctor.registered` to build a
local read-model (see Cross-Service Validation below), and publishes booking events that
Notification Service consumes.

---

## Services

| Service                | Port | Responsibility                                                                                                                      |
|------------------------|------|-------------------------------------------------------------------------------------------------------------------------------------|
| `api-gateway`          | 8080 | Single entry point for all client traffic; routes each path prefix to the owning service                                            |
| `appointment-service`  | 8081 | Booking, cancellation, availability/slot computation, read-model of valid patients/doctors, JWT validation, publishes domain events |
| `notification-service` | 8082 | Consumes appointment events from Kafka and persists notification records independently                                              |
| `patient-service`      | 8083 | Patient registration, login, JWT issuance (RS256), profile management                                                               |
| `doctor-service`       | 8084 | Doctor profile creation, specialty and language filtering, opening hours, registration event publishing                             |

---

## Kafka Event Model

| Event                   | Producer            | Consumer(s)                      |
|-------------------------|---------------------|----------------------------------|
| `patient.registered`    | patient-service     | appointment-service (read-model) |
| `doctor.registered`     | doctor-service      | appointment-service (read-model) |
| `appointment.booked`    | appointment-service | notification-service             |
| `appointment.cancelled` | appointment-service | notification-service             |

Kafka was chosen over synchronous REST for temporal decoupling: a consumer does not need to be available when an event
is produced. Events are retained in the log and consumed when the service is ready.

---

## Authentication and Authorization

Authentication uses **JWT with RS256** (asymmetric signing), per [ADR-004](docs/adr/ADR-004-JWT-Authentication.md).

* Patient Service issues PATIENT tokens (signs with the private key at registration and login).
* The public key is distributed to services that validate tokens. Appointment Service validates tokens with the same
  public key; it never holds the private key and cannot mint tokens.
* Validation follows a hybrid, zero-trust model: services validate tokens independently (the authoritative boundary).
  Gateway-level edge validation is a planned defense-in-depth addition and is not yet enabled.
* Tokens carry minimal claims: `sub` (the subject's id: the patient id in a PATIENT token), `role`, and `exp`.

**Identity is derived from the token, not the request body.** Booking takes the patient id from the token subject (
`sub`), so a caller cannot book on behalf of another user by supplying a different id. Cancellation enforces ownership:
a patient can only cancel their own appointment. Requests with the wrong token type or attempts to act on another user's
resource are rejected with `403`.

Public (no token required): doctor browsing, specialty listing, and availability. Booking and cancellation require a
valid patient token.

---

## Booking and Availability

Availability is modeled as a **first-class resource** (`GET /api/availability`), owned by Appointment Service because it
is computed from appointment data plus doctor opening hours.

* Slots are a fixed 30-minute grid generated from a doctor's opening hours for the requested day, minus already-taken (
  non-cancelled) appointments, minus past slots for the current day.
* **Double-booking is prevented at the database level, race-safe.** A Postgres **partial unique index** on
  `(doctor_id, date_time) WHERE status <> 'CANCELLED'` makes concurrent double-bookings impossible: the database rejects
  the second insert atomically, which is mapped to `409`. This avoids the check-then-insert race that application-level
  checks suffer from.
* Cancellation is a **soft delete** (status set to `CANCELLED`), so the audit trail is preserved and the slot becomes
  bookable again. The partial index is what allows a cancelled slot to be re-booked without losing the record.

---

## Cross-Service Validation (Read-Model)

Per [ADR-005](docs/adr/ADR-005-cross-service-validation.md), Appointment Service validates that a patient and doctor
exist before booking, **without** synchronous calls to Patient or Doctor Service. It consumes `patient.registered` and
`doctor.registered` into a local read-model (`ValidPatient`, `ValidDoctor`, including the doctor's opening hours), and
validates bookings against that projection. This preserves the async decoupling of ADR-002 while still enforcing
existence.

The consumer is idempotent (keyed on the domain id) and hydrates from the beginning of the topic on first run, so it
reconstructs state that predates it.

---

## Error Handling

All services return **RFC 9457 problem+json** error responses via a per-service `@RestControllerAdvice`:

| Condition                                                       | Status                                                          |
|-----------------------------------------------------------------|-----------------------------------------------------------------|
| Unknown patient/doctor, appointment/specialty not found         | `404`                                                           |
| Validation failure (with field-level detail), malformed request | `400`                                                           |
| Invalid credentials                                             | `401` (no/invalid token)                                        |
| Wrong token type, or acting on another user's resource          | `403`                                                           |
| Duplicate username/email, double-booking                        | `409`                                                           |
| Unexpected error                                                | `500` (generic message; cause logged server-side, never leaked) |

Duplicate detection (username, email, double-booking) is enforced by database unique constraints and translated to
`409`, rather than by race-prone pre-checks.

---

## Architecture Decisions (ADRs)

Documented in [`docs/adr/`](docs/adr/):

* [ADR-001](docs/adr/ADR-001-microservices-vs-monolith.md) — Microservices vs Modular Monolith
* [ADR-002](docs/adr/ADR-002-kafka-as-event-broker.md) — Kafka as Event Broker
* [ADR-003](docs/adr/ADR-003-db-per-service.md) — Database per Service
* [ADR-004](docs/adr/ADR-004-JWT-Authentication.md) — JWT Authentication (RS256, hybrid validation, token-derived
  identity)
* [ADR-005](docs/adr/ADR-005-cross-service-validation.md) — Cross-Service Validation via event-driven read-model
* [ADR-006](docs/adr/ADR-006-service-discovery.md) — Static Service discovery

Each ADR includes context, alternatives, trade-offs, and rationale.

---

## Failure and Edge Behavior

| Scenario                                           | Behavior                                                                                |
|----------------------------------------------------|-----------------------------------------------------------------------------------------|
| Notification Service down                          | Kafka retains the event; consumer resumes from last offset on restart, no data loss     |
| Concurrent double-booking                          | Rejected atomically by the partial unique index, returned as `409`                      |
| Booking outside opening hours or off the slot grid | Rejected with `400`                                                                     |
| Booking for an unknown patient/doctor              | Rejected with `404` (validated against the read-model)                                  |
| Cancelling another patient's appointment           | Rejected with `403`; no state change                                                    |
| Appointment not found on cancel                    | `404` problem+json; no partial state change                                             |
| Duplicate username/email                           | `409` problem+json                                                                      |
| Kafka downtime                                     | Booking and registration fail explicitly; no silent data loss                           |
| Duplicate event delivery                           | Read-model consumer is idempotent; event versioning and broader idempotency are Phase 3 |

---

## Current Limitations (Intentional)

* **Gateway-level JWT enforcement** is deferred. Per-service validation is the authoritative boundary and is enforced;
  the gateway edge filter is planned defense-in-depth.
* **Doctor authentication** is not yet implemented. Doctor profiles are publicly browsable by design; doctor creation is
  currently open (admin/onboarding auth is a later pass).
* **Refresh tokens** are not implemented; access tokens are valid for one hour.
* **Service discovery** is static per [ADR-006](docs/adr/ADR-006-service-discovery.md); Eureka/Consul is deferred
* **Reliability/observability** (tracing, metrics, retries, DLQ, event versioning) are Phase 3.

---

## Project Phases

### Phase 1 — Architecture Sandbox (complete)

Service decomposition · Kafka-based events · database-per-service · API Gateway · unit tests

### Phase 2 — Domain Modeling (substantially complete)

Patient auth (JWT RS256) · Doctor profiles, specialties, language filtering · availability/slot computation · race-safe
booking (partial unique index) · soft-delete cancellation · cross-service validation via event-driven read-model ·
per-service JWT validation with token-derived identity and ownership checks · RFC 9457 error handling across services ·
gateway routing for all services

### Phase 3 — Production & Intelligence Layer (planned)

Observability (logs, metrics, tracing) · reliability (idempotency, retries, DLQ, event versioning) · gateway-level JWT
edge validation · dockerization of all services · service discovery · AI-assisted symptom-to-specialty triage

---

## Local Setup

### Requirements

* Docker and Docker Compose (primary; runs the whole system)
* Java 21 and Maven 3.9+ (optional; only for running a service on the host)
* `curl` and `jq` (for the test script)

### Step 1 — Start Infrastructure

```bash
git clone https://github.com/ysfwrda/healthtech-booking.git
cd healthtech-booking
docker-compose up -d
```

Starts Kafka, Zookeeper, four PostgreSQL instances (one per service), and Kafka UI at `http://localhost:8090`.

### Step 2 — JWT Keys

The RSA key pair used to sign and validate tokens is committed for demo convenience (see the Authentication section). No
key generation is required to run the project. In a real deployment the private key would never be committed; it would
be injected via a secret manager or generated per environment.

```bash
openssl genrsa -out private.pem 2048
openssl rsa -in private.pem -pubout -out public.pem
```

### Step 3 — Start the Services

All services are containerized. Bring up the entire system (infrastructure plus all five services) with one command:

```bash
docker compose up --build
```

This builds each service image and starts them alongside Kafka, Zookeeper, and the four PostgreSQL instances, all on a
shared Docker network. The services connect to Kafka and their databases by container name.

To run a single service against the infrastructure for development, you can still run it on the host with Maven (it
falls back to `localhost` addresses by default):

```bash
cd appointment-service && mvn spring-boot:run
```

Dockerization of the services is planned (Phase 3).

### Step 4 — Exercise the Flow

Convenience path: run the full pipeline in one command (requires `curl` and `jq`):

```bash
bash scripts/test-flow.sh
```

It registers a patient and doctor, reads availability, books a slot, confirms the slot disappears, rejects a
double-booking, cancels, and confirms the slot reappears.

The individual calls (all through the gateway at `8080`) below show the API contract.

Register a patient (returns a JWT):

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "John", "lastName": "Doe",
    "username": "john.doe", "password": "secret",
    "dateOfBirth": "1990-01-01",
    "email": "john.doe@example.com",
    "insuranceType": "PRIVATE"
  }'
```

Create a doctor:

```bash
# 1. Get specialties
curl http://localhost:8080/api/specialties

# 2. Create a doctor (replace <specialty-id> with an id from step 1)
curl -X POST http://localhost:8080/api/doctors \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Jane", "lastName": "Smith",
    "email": "jane@clinic.com",
    "phoneNumber": "+49 30 1234567",
    "address": { "street": "Friedrichstrasse", "houseNumber": "12", "postalCode": "10117", "city": "Berlin", "country": "Germany" },
    "specialtyIds": ["<specialty-id>"],
    "openingHours": [ { "dayOfWeek": "MONDAY", "startTime": "09:00", "endTime": "17:00" } ],
    "languages": ["ENGLISH"]
  }'
```

Check a doctor's available slots (use a date whose weekday matches the opening hours; public, no token):

```bash
curl "http://localhost:8080/api/availability?doctorId=<doctor-id>&date=2026-07-13"
```

Book an appointment (requires the patient token from register/login; the patient id is taken from the token, not the
body):

```bash
curl -X POST http://localhost:8080/api/appointments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "doctorId": "<doctor-id>",
    "dateTime": "2026-07-13T10:00:00",
    "type": "INITIAL_CONSULTATION",
    "notes": "First visit, general checkup"
  }'
```

Cancel an appointment (requires the owning patient's token):

```bash
curl -X PUT http://localhost:8080/api/appointments/<appointment-id>/cancel \
  -H "Authorization: Bearer <token>"
```

Re-check availability after booking or cancelling to see the slot disappear, then reappear.

### Step 5 — Verify the Event Flow

* Kafka UI (`http://localhost:8090`): confirm `patient.registered`, `doctor.registered`, `appointment.booked`, and
  `appointment.cancelled` have messages.
* Appointment Service: confirm `valid_patient` / `valid_doctor` rows appear in `appointment_db` (the read-model).
* Notification Service: confirm the booking event was consumed and a notification record was saved in `notification_db`.

---

## Project Structure

```
healthtech-booking/
├── api-gateway/
├── appointment-service/
├── notification-service/
├── patient-service/
├── doctor-service/
├── infrastructure/
├── scripts/
│   └── test-flow.sh
├── docs/
│   ├── adr/
│   └── architecture/
└── docker-compose.yml
```

---

## Infrastructure Ports

| Resource             | Host Port | Notes                      |
|----------------------|-----------|----------------------------|
| Kafka                | 9092      | PLAINTEXT (host access)    |
| Kafka UI             | 8090      | Browse topics and messages |
| PostgreSQL (appt)    | 5432      | `appointment_db`           |
| PostgreSQL (notif)   | 5433      | `notification_db`          |
| PostgreSQL (patient) | 5434      | `patient_db`               |
| PostgreSQL (doctor)  | 5435      | `doctor_db`                |

---

## License

MIT
