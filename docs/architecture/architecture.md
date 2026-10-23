# Architecture

Diagrams of the system as it runs from `docker-compose.yml`. They are Mermaid, so GitHub renders them and they
live next to the code. Each diagram states what the code does today; planned work is called out separately.
When behavior changes, update the diagram in the same PR (see the repo's `CLAUDE.md`).

Contents:

1. [System overview](#1-system-overview): who talks to whom, and what each service owns
2. [Event flow](#2-event-flow): topics, the outbox, and consumers
3. [Booking sequence](#3-booking-sequence): one booking from click to notification
4. [Authentication and key distribution](#4-authentication-and-key-distribution)
5. [Gateway routes](#5-gateway-routes)
6. [Not yet in the diagrams](#6-not-yet-in-the-diagrams)

---

## 1. System overview

```mermaid
flowchart LR
    user(["Browser"])

    subgraph edge["Edge"]
        fe["<b>frontend</b><br/>React + Vite, served by nginx<br/>:5173"]
        gw["<b>api-gateway</b> :8080<br/>routing, JWT signature + expiry check,<br/>CORS, correlation id"]
    end

    subgraph services["Application services (Spring Boot)"]
        patient["<b>patient-service</b> :8083<br/>registration, login, profile,<br/>issues PATIENT JWTs"]
        doctor["<b>doctor-service</b> :8084<br/>registration, login, profiles,<br/>opening hours, issues DOCTOR JWTs"]
        appt["<b>appointment-service</b> :8081<br/>booking, change, cancel, availability,<br/>read-model of patients and doctors"]
        notif["<b>notification-service</b> :8082<br/>records a notification<br/>per appointment event"]
    end

    subgraph data["Databases (one per service, ADR-003)"]
        dbp[("patient_db<br/>+ outbox")]
        dbd[("doctor_db<br/>+ outbox")]
        dba[("appointment_db<br/>appointments, read-model, outbox")]
        dbn[("notification_db")]
    end

    subgraph bus["Event backbone"]
        kafka{{"Apache Kafka<br/>single broker"}}
        zk["Zookeeper"]
    end

    kafkaui["Kafka UI :8090<br/>(dev tool)"]

    user -->|"loads the SPA"| fe
    user -->|"REST calls, all traffic"| gw

    gw -->|"/api/auth, /api/patients"| patient
    gw -->|"/api/doctors, /api/specialties"| doctor
    gw -->|"/api/appointments (incl. /doctor), /api/availability"| appt

    patient --- dbp
    doctor --- dbd
    appt --- dba
    notif --- dbn

    patient -.->|"patient.registered"| kafka
    doctor -.->|"doctor.registered<br/>doctor.availability-updated"| kafka
    appt -.->|"appointment.booked<br/>appointment.cancelled<br/>appointment.changed<br/>appointment.confirmed<br/>appointment.reschedule-required"| kafka

    kafka -.->|"patient.registered<br/>doctor.registered<br/>doctor.availability-updated"| appt
    kafka -.->|"appointment.*"| notif

    kafka --- zk
    kafkaui -.->|"browses topics"| kafka

    classDef svc fill:#e8f1fb,stroke:#2b6cb0,color:#1a202c
    classDef store fill:#fdf3e1,stroke:#b7791f,color:#1a202c
    classDef infra fill:#eef2e6,stroke:#4a7c2a,color:#1a202c
    classDef edgeNode fill:#f3e8fb,stroke:#6b46c1,color:#1a202c
    class patient,doctor,appt,notif svc
    class dbp,dbd,dba,dbn store
    class kafka,zk,kafkaui infra
    class fe,gw edgeNode
```

**How to read it.** Solid arrows are synchronous HTTP (or a service's own database). Dotted arrows are
asynchronous Kafka events. No service calls another service directly: cross-service data moves only as events
(ADR-002, ADR-005). The frontend calls only the gateway. For local development, compose also publishes each
service's port, the databases and Kafka on the host, so a direct call can skip the gateway, though not the
service's own token check. Service addresses are static (ADR-006).

---

## 2. Event flow

Every event is written to an `outbox` table in the same transaction as the change it describes. A scheduled relay
publishes it to Kafka afterwards (ADR-008). Delivery is at-least-once, so consumers deduplicate (ADR-009).

```mermaid
flowchart LR
    subgraph producers["Producers (outbox in each: ADR-008)"]
        direction TB
        p1["patient-service"]
        p2["doctor-service<br/>(also the demo DoctorSeeder)"]
        p3["appointment-service"]
    end

    subgraph topics["Kafka topics"]
        direction TB
        t1["patient.registered"]
        t2["doctor.registered"]
        t6["doctor.availability-updated"]
        t3["appointment.booked"]
        t4["appointment.cancelled"]
        t5["appointment.changed"]
        t7["appointment.confirmed"]
        t8["appointment.reschedule-required"]
    end

    subgraph consumers["Consumers"]
        direction TB
        c1["appointment-service<br/>PatientEventConsumer<br/>upserts ValidPatient"]
        c2["appointment-service<br/>DoctorEventConsumer<br/>creates ValidDoctor with hours; for a known doctor refreshes names and autoConfirmBookings only"]
        c4["appointment-service<br/>DoctorAvailabilityConsumer<br/>applies hours + vacations if the version is newer, then flags appointments that no longer fit"]
        c3["notification-service<br/>AppointmentEventConsumer<br/>stores one notification per event id"]
    end

    p1 --> t1 --> c1
    p2 --> t2 --> c2
    p2 --> t6 --> c4
    p3 --> t3 --> c3
    p3 --> t4 --> c3
    p3 --> t5 --> c3
    p3 --> t7 --> c3
    p3 --> t8 --> c3
```

### Inside a producing service

```mermaid
flowchart LR
    req["Request handler<br/>@Transactional method"]
    biz[("Business tables<br/>e.g. appointments")]
    ob[("outbox table")]
    relay["OutboxRelay<br/>1 s after the previous run, batches of 100<br/>FOR UPDATE SKIP LOCKED"]
    prune["OutboxPruningJob<br/>daily at 03:00, deletes published rows<br/>older than 7 days"]
    k{{"Kafka"}}

    req -->|"1. change"| biz
    req -->|"2. DomainEventPublisher<br/>writes the event (same transaction)"| ob
    relay -->|"3. claim unpublished rows"| ob
    relay -->|"4. send, with X-Correlation-Id header"| k
    relay -->|"5. mark published<br/>(a failed send stays unpublished and is retried)"| ob
    prune --> ob
```

| Event                   | Producer            | Consumer(s)                      | Effect at the consumer                                     |
|-------------------------|---------------------|----------------------------------|------------------------------------------------------------|
| `patient.registered`    | patient-service     | appointment-service              | Upsert `ValidPatient` (read-model, ADR-005)                |
| `doctor.registered`     | doctor-service      | appointment-service              | Create `ValidDoctor` with its hours; for a known doctor refresh the names and `autoConfirmBookings` only (read-model) |
| `doctor.availability-updated` | doctor-service | appointment-service          | Replace the hours and vacations when the `version` is newer, then flag the booked appointments that no longer fit |
| `appointment.booked`    | appointment-service | notification-service             | Store a notification, deduplicated by event id             |
| `appointment.cancelled` | appointment-service | notification-service             | Same                                                       |
| `appointment.changed`   | appointment-service | notification-service             | Same                                                       |
| `appointment.confirmed` | appointment-service | notification-service             | Same                                                       |
| `appointment.reschedule-required` | appointment-service | notification-service | Same (the patient is asked to reschedule or cancel)    |

---

## 3. Booking sequence

```mermaid
sequenceDiagram
    autonumber
    actor P as Patient (browser)
    participant GW as api-gateway
    participant A as appointment-service
    participant DB as appointment_db
    participant R as OutboxRelay
    participant K as Kafka
    participant N as notification-service

    P->>GW: POST /api/appointments (Bearer PATIENT token)
    GW->>GW: resolve or generate X-Correlation-Id, then verify signature + expiry
    GW->>A: proxy request
    A->>A: validate token again (public key), patient id = token sub
    A->>DB: check ValidPatient / ValidDoctor read-model (404 if unknown)
    A->>A: slot grid, opening hours, not in the past, not on a vacation day (400)
    A->>DB: INSERT appointment (PENDING, or CONFIRMED for an auto-confirming doctor) + INSERT outbox row (one transaction)
    Note over DB: partial unique index on (doctor_id, date_time)<br/>WHERE status <> 'CANCELLED' rejects a double booking, mapped to 409
    A-->>GW: 201 Created
    GW-->>P: 201 Created

    loop every second
        R->>DB: claim unpublished outbox rows
    end
    R->>K: publish appointment.booked (X-Correlation-Id header)
    R->>DB: mark row published
    K-->>N: deliver appointment.booked
    N->>N: store notification (unique event id: a redelivery is ignored)
```

The doctor then confirms, cancels or changes the appointment through `/api/appointments/doctor`, each publishing its own event the
same way. When the doctor changes the opening hours or a vacation in doctor-service, `doctor.availability-updated` reaches
appointment-service, which flags the booked appointments that no longer fit and publishes `appointment.reschedule-required`.

If Kafka is down, the booking still succeeds; the event waits in the outbox and the relay sends it when Kafka
returns. If notification-service is down, Kafka retains the event and the consumer resumes from its last offset.

---

## 4. Authentication and key distribution

```mermaid
flowchart LR
    subgraph keys["./keys"]
        priv["private.pem"]
        pub["public.pem"]
    end

    patient["patient-service<br/>signs PATIENT tokens,<br/>validates them, role PATIENT"]
    doctor["doctor-service<br/>signs DOCTOR tokens,<br/>validates them, role DOCTOR"]
    gw["api-gateway<br/>verifies signature + expiry<br/>(no role check)"]
    appt["appointment-service<br/>verifies token, checks role<br/>and ownership"]
    notif["notification-service<br/>no JWT, no keys,<br/>no gateway route"]

    priv -->|"JWT_PRIVATE_KEY_PATH"| patient
    priv -->|"JWT_PRIVATE_KEY_PATH"| doctor
    pub --> patient
    pub --> doctor
    pub --> gw
    pub --> appt
```

Arrows show which key each service is configured to load (`JWT_PRIVATE_KEY_PATH`, `JWT_PUBLIC_KEY_PATH` in
`docker-compose.yml`).

Tokens are RS256 JWTs with the claims `sub`, `role`, `iat` and `exp` (ADR-004). Only patient-service and
doctor-service are configured with the private key, so only they sign tokens; the gateway and appointment-service
are configured with the public key only. Compose mounts the whole `./keys` directory read-only at `/run/keys` into all
four services, though, so `private.pem` is also readable inside the gateway and appointment-service containers. ADR-004 says the
gateway must never have the private key mounted; the compose file does not follow that yet.

Validation is layered: the gateway rejects unauthenticated traffic on protected paths at the edge, and every
service that serves protected endpoints validates the token itself, because the service is the authoritative
boundary.

---

## 5. Gateway routes

| Path prefix                                      | Target service      | Public at the gateway (no token)                         |
|--------------------------------------------------|---------------------|----------------------------------------------------------|
| `/api/auth/**`                                   | patient-service     | `POST /api/auth/register`, `POST /api/auth/login`        |
| `/api/patients/**`                               | patient-service     | none                                                     |
| `/api/doctors/**`                                | doctor-service      | `POST /api/doctors/register`, `POST /api/doctors/login`, every `GET` |
| `/api/specialties/**`                            | doctor-service      | every `GET`                                              |
| `/api/availability/**`                           | appointment-service | every `GET`                                              |
| `/api/appointments/**`                           | appointment-service | none (every request needs a PATIENT token)               |
| `/docs/{appointment,patient,doctor}/v3/api-docs` | each service        | `GET` (feeds the gateway's Swagger UI; these routes exist only while `SWAGGER_ENABLED` is true) |

The gateway also lets these through without a token: CORS preflight (`OPTIONS /**`), `/actuator/health`, its own
Swagger UI (`/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/swagger-config`) and `/error`. Every other request
needs a token with a valid signature and expiry (`anyRequest().authenticated()` in the gateway's `SecurityConfig`).
`notification-service` has no gateway route.

---

## 6. Not yet in the diagrams

These are planned (README, Project Phases) and are deliberately not drawn as if they existed:

* Consumer failure handling from ADR-009: poison-pill handling (`ErrorHandlingDeserializer`), a configured retry
  policy and a dead-letter topic. No consumer configures an error handler today.
* Metrics and distributed tracing (correlation ids are implemented, spans are not)
* Event versioning
* Dynamic service discovery (static per ADR-006)
* Doctor credential verification and a proper onboarding flow
* AI-assisted symptom-to-specialty triage
