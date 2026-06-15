# HealthTech Appointment Booking Platform

A microservice-based appointment booking system for healthcare providers.  
Built as a portfolio project to demonstrate event-driven architecture, distributed systems patterns, and AI-assisted triage.

> **Stack:** Java 21 · Spring Boot 3 · Apache Kafka · PostgreSQL · Docker · Gemini API

---

## Architecture Overview

```
┌─────────────┐     ┌───────────────────┐     ┌──────────────────────┐
│   Client    │────▶│    API Gateway     │────▶│  Appointment Service │
└─────────────┘     │ (Spring Cloud GW)  │     │  (Spring Boot + PG)  │
                    └───────────────────┘     └──────────┬───────────┘
                                                         │ AppointmentBooked
                                                         │ AppointmentCancelled
                                                    ┌────▼────────────────┐
                                                    │   Apache Kafka       │
                                                    └────┬────────────────┘
                                                         │
                                              ┌──────────▼───────────┐
                                              │ Notification Service  │
                                              │ (Spring Boot + Kafka) │
                                              └──────────────────────┘
```

### Services

| Service | Port | Responsibility |
|---|---|---|
| `api-gateway` | 8080 | Routing, rate limiting, auth filtering |
| `appointment-service` | 8081 | Book, cancel, and query appointments |
| `notification-service` | 8082 | Consume events, send notifications |

### Kafka Topics

| Topic | Producer | Consumer |
|---|---|---|
| `appointment.booked` | appointment-service | notification-service |
| `appointment.cancelled` | appointment-service | notification-service |

---

## Project Phases

- **Phase 1** ✅ API Gateway + Appointment Service + Notification Service (Kafka)
- **Phase 2** 🔄 Patient Service + Doctor Service + JWT Auth
- **Phase 3** 🔜 AI Triage Service (Gemini API / Ollama)

See [`docs/architecture/`](docs/architecture/) for bounded context maps and service diagrams.  
See [`docs/adr/`](docs/adr/) for architecture decision records.

---

## Local Setup

### Prerequisites

- Docker & Docker Compose
- Java 21 (for local development without Docker)
- Maven 3.9+

### Run with Docker Compose

```bash
# Clone the repository
git clone https://github.com/ysfwrda/healthtech-booking.git
cd healthtech-booking

# Start all infrastructure and services
docker-compose up -d

# Verify all containers are running
docker-compose ps
```

### Verify the Setup

```bash
# Book an appointment (Appointment Service)
curl -X POST http://localhost:8080/api/appointments \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "patient-001",
    "doctorId": "doctor-001",
    "dateTime": "2025-09-01T10:00:00"
  }'

# Check Notification Service logs (should show event received)
docker-compose logs notification-service
```

### Stop

```bash
docker-compose down
```

---

## Architecture Decisions

Key decisions are documented as ADRs (Architecture Decision Records) in [`docs/adr/`](docs/adr/):

- [ADR-001](docs/adr/ADR-001-microservices-vs-monolith.md) — Microservices vs. Modular Monolith
- [ADR-002](docs/adr/ADR-002-kafka-as-event-broker.md) — Kafka as Event Broker
- [ADR-003](docs/adr/ADR-003-db-per-service.md) — Database per Service Pattern

---

## Project Structure

```
healthtech-booking/
├── docker-compose.yml
├── README.md
├── docs/
│   ├── architecture/
│   │   └── system-overview.md
│   └── adr/
│       ├── ADR-001-microservices-vs-monolith.md
│       ├── ADR-002-kafka-as-event-broker.md
│       └── ADR-003-db-per-service.md
├── api-gateway/
├── appointment-service/
├── notification-service/
└── infrastructure/
    ├── kafka/
    └── postgres/
```

---

## License

MIT
