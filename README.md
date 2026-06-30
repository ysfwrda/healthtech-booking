# HealthTech Appointment Booking Platform

A microservice-based appointment booking platform designed as a **distributed systems and event-driven architecture sandbox**.

This project explores how distributed systems behave when architectural concerns are separated early and gradually re-integrated as domain complexity increases. The goal is not only to build services, but to understand how system behavior changes under different architectural constraints.

This project intentionally separates concerns across phases:

* **Phase 1:** Distributed system architecture fundamentals (service boundaries, async communication, data ownership)
* **Phase 2:** Domain complexity (patients, doctors, authentication, business rules)
* **Phase 3:** Production-grade concerns (observability, reliability, AI-assisted workflows)

---

## Tech Stack

Java 21 · Spring Boot 3 · Apache Kafka · PostgreSQL · Docker · MapStruct · JWT (RS256)

---

## Architecture Overview (Phase 2)

```
┌─────────────┐     ┌───────────────────┐
│   Client    │────▶│    API Gateway     │
└─────────────┘     └─────────┬─────────┘
                              │
          ┌───────────────────┼───────────────────┐
          │                   │                   │
          ▼                   ▼                   ▼
┌─────────────────┐  ┌────────────────┐  ┌────────────────┐
│ Patient Service │  │   Appointment  │  │ Doctor Service │
│  (auth + JWT)   │  │    Service     │  │  (profiles +   │
│                 │  │                │  │   filtering)   │
└────────┬────────┘  └───────┬────────┘  └───────┬────────┘
         │                   │                   │
         │ patient.registered│ appointment.booked│ doctor.registered
         │                   │ appointment.      │
         │                   │ cancelled         │
         └───────────────────┼───────────────────┘
                             │
                    ┌────────▼────────┐
                    │  Apache Kafka   │
                    └────────┬────────┘
                             │
                    ┌────────▼──────────┐
                    │ Notification Svc  │
                    └───────────────────┘
```

---

## Services

| Service                | Port | Responsibility                          |
| ---------------------- | ---- | --------------------------------------- |
| `api-gateway`          | 8080 | Single entry point for all client requests. Centralizes routing, and in future phases: auth filtering and rate limiting |
| `appointment-service`  | 8081 | Core booking and cancellation logic, publishes domain events to Kafka |
| `notification-service` | 8082 | Consumes appointment events from Kafka and persists notification records independently |
| `patient-service`      | 8083 | Patient registration, login, JWT issuance (RS256), and profile management |
| `doctor-service`       | 8084 | Doctor profile creation, specialty filtering, language filtering, and registration event publishing |

---

## Kafka Event Model

| Event                   | Producer            | Consumer(s)          |
| ----------------------- | ------------------- | -------------------- |
| `appointment.booked`    | appointment-service | notification-service |
| `appointment.cancelled` | appointment-service | notification-service |
| `patient.registered`    | patient-service     | — (future consumers) |
| `doctor.registered`     | doctor-service      | — (future consumers) |

Kafka was chosen over direct REST calls to achieve temporal decoupling — the Notification Service does not need to be available when an appointment is booked. Events are retained in the log and consumed when the service is ready.

---

## Authentication

Patient authentication uses **JWT with RS256** (asymmetric signing):

* The private key signs tokens at registration and login
* The public key will be shared with other services (e.g. api-gateway) for token verification
* Key pair is loaded from PEM files at `keys/private.pem` and `keys/public.pem` in the patient-service classpath
* Token expiry is configured via `app.jwt.expiration` (seconds)

---

## Key Architectural Focus

### Phase 1 — Distributed System Fundamentals
* Service boundaries and decomposition
* Asynchronous communication via Kafka
* Event-driven architecture patterns
* Decoupling between services
* Database-per-service principle

### Phase 2 — Domain Modeling
* Patient identity and authentication (JWT RS256)
* Doctor profiles with specialties, languages, and opening hours
* Domain events for cross-service awareness (`patient.registered`, `doctor.registered`)
* Specialty seeding and filtering by specialty/language

> Business rules are intentionally lean to keep focus on architectural mechanics.

---

## Architecture Decisions (ADRs)

Key decisions documented in [`docs/adr/`](docs/adr/):

* [ADR-001](docs/adr/ADR-001-microservices-vs-modular-monolith.md) — Microservices vs Modular Monolith
* [ADR-002](docs/adr/ADR-002-kafka-as-event-broker.md) — Kafka as Event Broker
* [ADR-003](docs/adr/ADR-003-db-per-service.md) — Database per Service Pattern
* [ADR-004](docs/adr/ADR-004-JWT-Authentication.md) — JWT Authentication
* [ARD-005](docs/adr/ADR-005-cross-service-validation.md) - Cross-service validation

Each ADR includes context, alternatives considered, trade-offs, and decision rationale.

---

## Failure Scenarios

| Scenario | Behavior |
| -------- | -------- |
| Notification Service down | Kafka retains the event. Consumer resumes from last offset on restart — no data loss |
| Duplicate events | Not handled yet. Idempotency planned for Phase 3 |
| Kafka downtime | Appointment booking and patient/doctor registration fail. No silent data loss — failure is explicit |
| Appointment not found on cancel | `RuntimeException` thrown with message. No partial state change |
| Duplicate patient username | `UsernameAlreadyExistsException` thrown at registration |
| Duplicate doctor email | `EmailAlreadyExistsException` thrown at registration |

---

## Current Limitations (Intentional — Phase 3 Scope)

### Reliability & Observability (Phase 3)
* Centralized logging strategy
* Distributed tracing
* Metrics (Prometheus/Grafana)
* Monitoring dashboards

### Robust Distributed System Behavior (Phase 3)
* Idempotency handling
* Retry policies
* Failure recovery flows
* Dead-letter queues
* Event versioning

### Domain Complexity (Phase 3)
* Appointment service does not yet validate patient/doctor existence
* API Gateway does not yet enforce JWT authentication

---

## Project Phases

### Phase 1 — Architecture Sandbox

✔ Service decomposition  
✔ Kafka-based event communication  
✔ Database-per-service  
✔ API Gateway  
✔ Unit tests (AppointmentService)

### Phase 2 — Domain Modeling

✔ Patient Service (registration, login, JWT RS256)  
✔ Doctor Service (profiles, specialties, language filtering)  
✔ Domain events: `patient.registered`, `doctor.registered`  
✔ Unit tests (DoctorService, PatientService)

### Phase 3 — Production & Intelligence Layer

🔜 Observability (logs, metrics, tracing)  
🔜 Reliability patterns (idempotency, retries, DLQ)  
🔜 JWT validation in API Gateway  
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

This starts Kafka, Zookeeper, 4 PostgreSQL instances (one per service), and Kafka UI at `http://localhost:8090`.

### Step 2 — Generate JWT Key Pair

The patient-service requires an RSA key pair. Generate and place the PEM files in `patient-service/src/main/resources/keys/`:

```bash
openssl genrsa -out private.pem 2048
openssl rsa -in private.pem -pubout -out public.pem
```

### Step 3 — Start Services

Services are started separately via IntelliJ or Maven during development.
Dockerfiles for all services are planned for Phase 3.

Start in this order:
1. `patient-service` — port 8083
2. `doctor-service` — port 8084
3. `appointment-service` — port 8081
4. `notification-service` — port 8082
5. `api-gateway` — port 8080

### Step 4 — Test the Flow

Register a patient:

```bash
curl -X POST http://localhost:8083/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "John",
    "lastName": "Doe",
    "username": "john.doe",
    "password": "secret",
    "dateOfBirth": "2005-01-01",
    "email": "john.doe@gmail.com",
    "insuranceType": "PRIVATE"
  }'
```

Create a doctor:

```bash
# 1. Get available specialties
curl -X GET http://localhost:8084/api/specialties

# 2. Create a doctor (replace <specialty-uuid> with an actual id from step 1)
curl -X POST http://localhost:8084/api/doctors \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Jane",
    "lastName": "Smith",
    "email": "jane@clinic.com",
    "phoneNumber": "+49 30 1234567",
    "address": {
      "street": "Friedrichstrasse 100",
	  "houseNumber": "12",
      "postalCode": "10117",
	  "city": "Berlin",
      "country": "Germany"
    },
    "specialtyIds": ["<Specialty Ids>"],
    "openingHours": [
      {
        "dayOfWeek": "MONDAY",
        "startTime": "09:00",
        "endTime": "17:00"
      }
    ],
    "languages": ["ENGLISH"]
  }'
```

Book an appointment through the API Gateway:

```bash
curl -X POST http://localhost:8080/api/appointments \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "<id of patient created>",
    "doctorId": "<id of doctor created>",
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

### Step 5 — Verify the Event Flow

* Kafka UI: `http://localhost:8090` — verify `appointment.booked`, `patient.registered`, and `doctor.registered` topics have messages
* Notification Service logs — verify `appointment.booked` event was consumed
* pgAdmin — connect to `notification_db` on port `5433` and verify notification record was saved

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
├── docs/
│   ├── adr/
│   └── architecture/
└── docker-compose.yml
```

---

## Infrastructure Ports

| Resource            | Host Port | Notes                      |
| ------------------- | --------- | -------------------------- |
| Kafka               | 9092      | PLAINTEXT (host access)    |
| Kafka UI            | 8090      | Browse topics and messages |
| PostgreSQL (appt)   | 5432      | `appointment_db`           |
| PostgreSQL (notif)  | 5433      | `notification_db`          |
| PostgreSQL (patient)| 5434      | `patient_db`               |
| PostgreSQL (doctor) | 5435      | `doctor_db`                |

---

## License

MIT
