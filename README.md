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

Phase 2 is complete: end-to-end booking with JWT-secured, race-safe reservations and RFC 9457 error
handling.

---

## Tech Stack

Java 21 · Spring Boot 3 · Apache Kafka · PostgreSQL · Spring Cloud Gateway (MVC) · Spring Security (OAuth2 Resource
Server) · MapStruct · JWT (RS256) · Docker · React (Vite, TypeScript)

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
| `doctor-service`       | 8084 | Doctor self-registration, login, JWT issuance (RS256), specialty and language filtering, opening hours, registration event publishing |
| `frontend`             | 5173 | React (Vite, TypeScript) client. Talks to every service exclusively through the API Gateway. Not yet containerized in `docker-compose.yml`; run separately, see Local Setup |

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
* Doctor Service issues DOCTOR tokens (signs with the same shared private key at self-registration and login).
* The public key is distributed to services that validate tokens. Appointment Service validates tokens with the same
  public key; it never holds the private key and cannot mint tokens.
* Validation follows a hybrid, zero-trust model: services validate tokens independently (the authoritative boundary).
  Gateway-level edge validation is a planned defense-in-depth addition and is not yet enabled.
* Tokens carry minimal claims: `sub` (the subject's id: the patient id in a PATIENT token, the doctor id in a DOCTOR
  token), `role`, and `exp`.

**Identity is derived from the token, not the request body.** Booking takes the patient id from the token subject (
`sub`), so a caller cannot book on behalf of another user by supplying a different id. Cancellation enforces ownership
and requires a PATIENT token, consistent with booking: a patient can only cancel their own appointment. Requests with
the wrong token type or attempts to act on another user's resource are rejected with `403`.

Public (no token required): patient and doctor registration/login, doctor browsing, specialty listing, and
availability. Booking and cancellation require a valid patient token.

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

## Observability

Every service, including the gateway, generates or propagates a correlation ID via a `CorrelationIdFilter` and logs
it through MDC (`%5p [%X{correlationId}]` in the log pattern). The gateway resolves or generates the id first, then
overwrites the outbound header before proxying, so the same id threads through the gateway and every downstream
service call for a single request.

Metrics, distributed tracing (spans), retries, and a dead-letter queue for Kafka consumers are not implemented yet
and remain Phase 3 work; see Current Limitations.

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
* **Doctor onboarding trust** is intentionally shallow in Phase 2. Self-registration (`POST /api/doctors/register`) is
  public and issues a DOCTOR token immediately: it proves someone can create an account, not that they are a verified
  provider. Production would gate provider visibility and trust behind credential verification (for example, an
  Arztekammer number) and identity checks in a proper onboarding flow; that distinction, and closing it, is deferred
  to Phase 3. Admin-provisioned doctor accounts are deferred too, as YAGNI at the current scale. Browsing (`GET`)
  stays public by design.
* **Refresh tokens** are not implemented; access tokens are valid for one hour.
* **Service discovery** is static per [ADR-006](docs/adr/ADR-006-service-discovery.md); Eureka/Consul is deferred
* **Reliability/observability**: correlation-ID propagation and structured logging are implemented across all
  services and the gateway (see Observability above). Distributed tracing, metrics, retries, DLQ, and event
  versioning are still Phase 3.

---

## Project Phases

### Phase 1 — Architecture Sandbox (complete)

Service decomposition · Kafka-based events · database-per-service · API Gateway · unit tests

### Phase 2 — Domain Modeling (complete)

Patient auth (JWT RS256) · Doctor profiles, specialties, language filtering · availability/slot computation · race-safe
booking (partial unique index) · soft-delete cancellation · cross-service validation via event-driven read-model ·
per-service JWT validation with token-derived identity and ownership checks · RFC 9457 error handling across services ·
gateway routing for all services · Testcontainers integration coverage (concurrency-verified booking, cross-service JWT rejection, read-model projection, ownership enforcement)

### Phase 3: Production & Intelligence Layer (in progress)

Correlation-ID propagation and structured logging (done, see Observability) · metrics and distributed tracing ·
reliability (idempotency, retries, DLQ, event versioning) · gateway-level JWT edge validation · service discovery · AI-assisted symptom-to-specialty triage

---

## Testing

Each service is tested at three layers, isolating a different concern at each level: unit tests for business logic,
slice tests for the web/security contract, and integration tests for what only a real database, Kafka broker, and
full Spring context can catch.

### Unit tests

Plain Mockito tests against a single class, no Spring context, no I/O. These cover business logic and edge cases in
isolation: `AppointmentServiceTest`, `DoctorServiceTest`, `SpecialtyServiceTest`, `DoctorSpecificationsTest`,
`SpecialtySeederTest`, `AuthServiceTest`, `PatientServiceTest`, `JwtTokenProviderTest`, `NotificationServiceTest`,
and `AppointmentEventConsumerTest` (the Kafka listener method tested with a mocked service dependency).

### Slice tests

`@WebMvcTest` tests (`AppointmentControllerTest`, `AvailabilityControllerTest`, `DoctorControllerTest`,
`SpecialtyControllerTest`, `PatientControllerTest`) load only the web layer: the controller, validation, and the
real Spring Security authorization rules, with the service layer mocked. Each service's `SecurityConfig` holds only
those authorization rules; the `JwtDecoder` bean (the thing that actually needs a real key) lives in a separate
`JwtDecoderConfig`. A slice test imports `SecurityConfig` to get the real permitAll/authenticated rules, then
supplies its own `@MockitoBean JwtDecoder`, so `JwtDecoderConfig` and the `RsaKeyProperties` binding behind it are
never loaded. These tests pass in a clean checkout with no `keys/` directory present at all, and run in milliseconds
since there's no database or broker involved.

### Integration tests (Testcontainers)

Four services have full application-context integration tests backed by Testcontainers instead of mocks, exercising
real persistence, real RS256 signature validation, and in some cases a real Kafka broker:

* `patient-service` (`AuthIntegrationTest`): registration and duplicate-username/email conflict flows against a real
  Postgres container. Kafka is mocked with `@MockitoBean KafkaTemplate`, and the test asserts `patient.registered`
  is published with the expected payload.
* `doctor-service` (`DoctorIntegrationTest`): the same pattern, plus JWT-secured write paths, using a throwaway RSA
  key pair generated inside the test (its own `TestJwtFactory`), so it never reads `keys/private.pem`.
* `appointment-service` (`AppointmentIntegrationTest`, `AvailabilityIntegrationTest`): booking, cancellation,
  ownership checks, and availability against a real Postgres container, with Kafka mocked and JWTs minted the same
  throwaway way. `ReadModelProjectionIntegrationTest` instead runs a real `ConfluentKafkaContainer` alongside
  Postgres, publishes real `patient.registered`/`doctor.registered` events, and asserts the read-model consumer
  projects them correctly, exercising the actual Kafka listener rather than a mock.
* `notification-service` (`NotificationConsumerIntegrationTest`): the same real-Kafka-container approach, publishing
  a real `appointment.booked` event and asserting a notification row gets persisted, verifying the consumer wiring
  end to end.

The `*ApplicationTests` smoke tests (`PatientServiceApplicationTests`, `DoctorServiceApplicationTests`,
`AppointmentServiceApplicationTests`) also load the full application, including `JwtDecoderConfig`, and each starts
its own Testcontainers Postgres, so they no longer silently depend on a manually-run `docker-compose` stack being up.
They read from the same `keys/` directory described in JWT Keys below, the one and only key location in this repo;
a real deployment never uses this location either, since it overrides `JWT_PRIVATE_KEY_PATH`/`JWT_PUBLIC_KEY_PATH`
to point at a mounted secret instead (see the Docker Compose table below).

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

The RSA key pair used to sign and validate tokens lives at the repo root, outside every service's resources, so there
is a single source of truth instead of copies baked into each service jar:

```
keys/
  private.pem   (gitignored, never committed)
  public.pem    (committed for clone-and-run convenience)
```

Patient Service signs tokens with the private key. Appointment Service and the API Gateway validate tokens with the
public key; neither holds the private key and neither can mint tokens.

Generate your own matching pair (this overwrites the committed `keys/public.pem` locally with one that matches your
freshly generated private key; that is expected, since a private key generated on your machine can only ever be
verified by the public key derived from it):

```bash
mkdir -p keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/private.pem
openssl rsa -in keys/private.pem -pubout -out keys/public.pem
```

`keys/private.pem` is gitignored and must be generated locally; in a real deployment it would be injected via a secret
manager instead. If `keys/public.pem` ever drifts from the private key actually in use (for example, a stale commit
without regenerating it locally), token verification fails closed with a signature error, not a silent bypass.

Each service reads its key path from an environment variable with a local-host default, matching the existing
`${VAR:default}` pattern used for database and Kafka settings elsewhere in this project:

| Service              | Env var                | Default (host)              |
|----------------------|-------------------------|------------------------------|
| patient-service      | `JWT_PRIVATE_KEY_PATH`, `JWT_PUBLIC_KEY_PATH` | `file:../keys/private.pem`, `file:../keys/public.pem` |
| doctor-service        | `JWT_PRIVATE_KEY_PATH`, `JWT_PUBLIC_KEY_PATH` | `file:../keys/private.pem`, `file:../keys/public.pem` |
| appointment-service   | `JWT_PUBLIC_KEY_PATH`   | `file:../keys/public.pem`    |

The host defaults assume the process runs from within its own module directory (`cd patient-service && mvn
spring-boot:run`, matching Step 3 below), so `../keys/` resolves to the repo-root `keys/` directory. Under Docker
Compose, `./keys` is mounted read-only into each container at `/run/keys` and the env vars are set to
`file:/run/keys/...` accordingly.

Note: doctor-service now issues DOCTOR tokens too (self-registration and login), signing with the same shared private
key patient-service uses for PATIENT tokens, per [ADR-004](docs/adr/ADR-004-JWT-Authentication.md). Doctor
registration is public by design; there is no admin/doctor role check gating who can register (see Current
Limitations). Browsing and specialty reads stay public.
The API Gateway does not yet validate tokens at the edge (per the Authentication section below, that is still
planned). No `JwtDecoder` or `JWT_PUBLIC_KEY_PATH` wiring exists in `api-gateway` yet; that scaffolding still needs
to be added when gateway-level validation lands.

### Step 3 — Start the Services

All services are containerized. Bring up the entire system (infrastructure plus all five services) with one command:

```bash
docker-compose up --build
```

This builds each service image and starts them alongside Kafka, Zookeeper, and the four PostgreSQL instances, all on a
shared Docker network. The services connect to Kafka and their databases by container name.

To run a single service against the infrastructure for development, you can still run it on the host with Maven (it
falls back to `localhost` addresses by default):

```bash
cd appointment-service && mvn spring-boot:run
```
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

Register a doctor (public, no token required; issues a DOCTOR JWT immediately, see Current Limitations for what that
does and doesn't vouch for):

```bash
# 1. Get specialties
curl http://localhost:8080/api/specialties

# 2. Register a doctor (replace <specialty-id> with an id from step 1)
curl -X POST http://localhost:8080/api/doctors/register \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Jane", "lastName": "Smith",
    "email": "jane@clinic.com",
    "password": "secret123",
    "phoneNumber": "+49 30 1234567",
    "address": { "street": "Friedrichstrasse", "houseNumber": "12", "postalCode": "10117", "city": "Berlin", "country": "Germany" },
    "specialtyIds": ["<specialty-id>"],
    "openingHours": [ { "dayOfWeek": "MONDAY", "startTime": "09:00", "endTime": "17:00" } ],
    "languages": ["ENGLISH"]
  }'
```

Doctor login (returns a fresh DOCTOR JWT for an already-registered doctor, including any of the seeded demo doctors
below):

```bash
curl -X POST http://localhost:8080/api/doctors/login \
  -H "Content-Type: application/json" \
  -d '{ "email": "jane@clinic.com", "password": "secret123" }'
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

### Demo Data (Seeded)

`DoctorSeeder` runs at doctor-service startup (after `SpecialtySeeder`, which it depends on) and seeds six demo
doctors, idempotently (skipped on restart if the email already exists). Each covers multiple specialties and
languages, with opening hours across several weekdays, so filtering and availability have realistic overlap to
explore. Each publishes `doctor.registered` on creation, same as a real self-registration, so they're immediately
bookable through the normal flow.

All seeded doctors share one password: **`demo12345`**. Log in as any of them via `POST /api/doctors/login`:

| Email                              | Specialties                              | Languages                  |
|-------------------------------------|-------------------------------------------|-----------------------------|
| `anna.weber@demo.healthtech.com`     | General Practice, Cardiology              | German, English             |
| `mehmet.yilmaz@demo.healthtech.com`  | Cardiology, Orthopedics                   | Turkish, German              |
| `sophie.dubois@demo.healthtech.com`  | Dermatology, Gynecology                   | French, English              |
| `elena.rossi@demo.healthtech.com`    | Pediatrics, Dermatology, Gynecology       | Italian, Spanish             |
| `omar.haddad@demo.healthtech.com`    | Neurology, General Practice               | Arabic, Persian, English     |
| `katarina.petrov@demo.healthtech.com`| Orthopedics, Neurology, Pediatrics        | Russian, English             |

```bash
curl -X POST http://localhost:8080/api/doctors/login \
  -H "Content-Type: application/json" \
  -d '{ "email": "anna.weber@demo.healthtech.com", "password": "demo12345" }'
```

### Step 5 — Verify the Event Flow

* Kafka UI (`http://localhost:8090`): confirm `patient.registered`, `doctor.registered`, `appointment.booked`, and
  `appointment.cancelled` have messages.
* Appointment Service: confirm `valid_patient` / `valid_doctor` rows appear in `appointment_db` (the read-model).
* Notification Service: confirm the booking event was consumed and a notification record was saved in `notification_db`.

### Step 6 — Run the Frontend

The frontend is a React (Vite, TypeScript) client that talks to every service exclusively through the API Gateway.
It is not containerized, so run it on the host against the Docker Compose stack from Step 1:

```bash
cd frontend
cp .env.example .env.local   # VITE_API_BASE=http://localhost:8080, points at the gateway
npm install
npm run dev
```

Opens at `http://localhost:5173`. Covers both patient and doctor self-service flows end to end: patient
register/login, doctor search with specialty/language filters, doctor detail with formatted opening hours and
live availability, appointment booking/cancellation, and doctor register/login (`/doctors/register`,
`/doctors/login`), mirroring the API described in Step 4.

---

## Project Structure

```
healthtech-booking/
├── api-gateway/
├── appointment-service/
├── notification-service/
├── patient-service/
├── doctor-service/
├── frontend/
├── infrastructure/
├── keys/
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
