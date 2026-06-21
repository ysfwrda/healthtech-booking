# HealthTech Appointment Booking Platform

A microservice-based appointment booking platform designed as a **distributed systems and event-driven architecture sandbox**.

This project explores how distributed systems behave when architectural concerns are separated early and gradually re-integrated as domain complexity increases. The goal is not only to build services, but to understand how system behavior changes under different architectural constraints.

This project intentionally separates concerns across phases:

* **Phase 1:** Distributed system architecture fundamentals (service boundaries, async communication, data ownership)
* **Phase 2:** Domain complexity (patients, doctors, authentication, business rules)
* **Phase 3:** Production-grade concerns (observability, reliability, AI-assisted workflows)

---

## Tech Stack

Java 21 · Spring Boot 3 · Apache Kafka · PostgreSQL · Docker · MapStruct · Gemini API (planned)

---

## Architecture Overview (Phase 1)

Phase 1 focuses intentionally on **architecture mechanics rather than business complexity**.

```
┌─────────────┐     ┌───────────────────┐     ┌──────────────────────┐
│   Client    │────▶│    API Gateway     │────▶│ Appointment Service  │
└─────────────┘     └───────────────────┘     └──────────┬───────────┘
                                                         │
                                     Appointment Events  │
                           (booked / cancelled)
                                                    ┌────▼────────────────┐
                                                    │    Apache Kafka     │
                                                    └────┬────────────────┘
                                                         │
                                              ┌──────────▼───────────┐
                                              │ Notification Service  │
                                              └──────────────────────┘
```

---

## Services

| Service                | Port | Responsibility                          |
| ---------------------- | ---- | --------------------------------------- |
| `api-gateway`          | 8080 | Single entry point for all client requests. Centralizes routing, and in future phases: auth filtering and rate limiting |
| `appointment-service`  | 8081 | Core booking and cancellation logic, publishes domain events to Kafka |
| `notification-service` | 8082 | Consumes appointment events from Kafka and persists notification records independently |

---

## Kafka Event Model

| Event                   | Producer            | Consumer             |
| ----------------------- | ------------------- | -------------------- |
| `appointment.booked`    | appointment-service | notification-service |
| `appointment.cancelled` | appointment-service | notification-service |

Kafka was chosen over direct REST calls to achieve temporal decoupling — the Notification Service does not need to be available when an appointment is booked. Events are retained in the log and consumed when the service is ready.

---

## Key Architectural Focus (Phase 1)

Phase 1 deliberately isolates **distributed system fundamentals**:

* Service boundaries and decomposition
* Asynchronous communication via Kafka
* Event-driven architecture patterns
* Decoupling between services
* Database-per-service principle

> Business logic is intentionally minimal in Phase 1 to keep the focus on architectural mechanics.

---

## Architecture Decisions (ADRs)

Key decisions documented in [`docs/adr/`](docs/adr/):

* [ADR-001](docs/adr/ADR-001-microservices-vs-modular-monolith.md) — Microservices vs Modular Monolith
* [ADR-002](docs/adr/ADR-002-kafka-as-event-broker.md) — Kafka as Event Broker
* [ADR-003](docs/adr/ADR-003-db-per-service.md) — Database per Service Pattern

Each ADR includes context, alternatives considered, trade-offs, and decision rationale.

---

## Failure Scenarios (Phase 1 Behavior)

| Scenario | Behavior |
| -------- | -------- |
| Notification Service down | Kafka retains the event. Consumer resumes from last offset on restart — no data loss |
| Duplicate events | Not handled in Phase 1. Idempotency planned for Phase 3 |
| Kafka downtime | Appointment booking fails. No silent data loss — failure is explicit |
| Appointment not found on cancel | `RuntimeException` thrown with message. No partial state change |

---

## Current Limitations (Intentional - Phase 1 Scope)

The following are **not part of Phase 1 by design**:

### Reliability & Observability (Phase 3)

* Centralized logging strategy
* Distributed tracing
* Metrics (Prometheus/Grafana)
* Monitoring dashboards

### Robust Distributed System Behavior (Phase 2–3)

* Idempotency handling
* Retry policies
* Failure recovery flows
* Dead-letter queues
* Event versioning

### Domain Complexity (Phase 2)

* Patient and doctor management
* Authentication & authorization (JWT)
* Business rules and constraints

---

## Project Phases

### Phase 1 (Current) – Architecture Sandbox

✔ Service decomposition  
✔ Kafka-based event communication  
✔ Database-per-service  
✔ API Gateway  
✔ Unit tests (AppointmentService)

### Phase 2 – Domain Modeling

🔄 Patient Service  
🔄 Doctor Service  
🔄 JWT authentication  
🔄 Business rules and constraints

### Phase 3 – Production & Intelligence Layer

🔜 Observability (logs, metrics, tracing)  
🔜 Reliability patterns (idempotency, retries, DLQ)  
🔜 AI-assisted triage (Gemini API / Ollama)  
🔜 Dockerfiles for all services

---

## Local Setup

### Requirements

* Docker & Docker Compose
* Java 21
* Maven 3.9+

### Step 1 — Start Infrastructure

```bash
git clone https://github.com/ysfwrda/healthtech-booking.git
cd healthtech-booking
docker-compose up -d
```

This starts Kafka, Zookeeper, PostgreSQL (3 instances), and Kafka UI at `http://localhost:8090`.

### Step 2 — Start Services

Services are started separately via IntelliJ or Maven during development.
Dockerfiles for all services are planned for Phase 3.

Start in this order:
1. `appointment-service` — port 8081
2. `notification-service` — port 8082
3. `api-gateway` — port 8080

### Step 3 — Test the Flow

Book an appointment through the API Gateway:

```bash
curl -X POST http://localhost:8080/api/appointments \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "doctorId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "dateTime": "2025-09-01T10:00:00",
    "duration": 30,
    "type": "INITIAL_CONSULTATION",
    "notes": "First visit, general checkup"
  }'
```

Cancel an appointment:

```bash
curl -X PUT http://localhost:8080/api/appointments/{id}/cancel
```

### Step 4 — Verify the Event Flow

* Kafka UI: `http://localhost:8090` — verify `appointment.booked` topic has a message
* Notification Service logs — verify event was consumed
* pgAdmin — connect to `notification_db` on port `5433` and verify notification record was saved

---

## Project Structure

```
healthtech-booking/
├── api-gateway/
├── appointment-service/
├── notification-service/
├── infrastructure/
├── docs/
│   ├── adr/
│   └── architecture/
└── docker-compose.yml
```

---

## License

MIT
