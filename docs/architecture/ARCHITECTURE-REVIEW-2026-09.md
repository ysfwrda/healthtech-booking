# Architecture Review: HealthTech Appointment Booking Platform

Date: 2026-09-06
Scope: all five backend services, API gateway, frontend, Docker Compose, CI, scripts, the seven ADRs, and the test suites, at commit `2155da4` on `main`.
Method: static review of every source file. Test suites were read, not executed, because the integration tests need Docker for Testcontainers.

---

## 1. Executive summary

This is a well-reasoned, honestly documented event-driven system whose architectural thinking is ahead of its operational execution. The ADRs, the ADR-004 correction in particular, and the three-layer test strategy are the strongest assets and are rare in portfolio projects. The weakest areas are the ones an event-driven system is judged on in production: the database write and the Kafka publish are not atomic (dual write without an outbox), consumers have no poison-message or dead-letter handling, one consumer is not idempotent, and there is no schema migration tooling. For the German market specifically, the project handles health-related personal data with no data-protection (DSGVO) reasoning at all.

Scorecard, 1 to 5:

| Aspect | Score | One-line reason |
|---|---|---|
| Architecture and service boundaries | 4.0 | Clean async decoupling, read-model pattern done properly, gateway kept narrow |
| Documentation and ADRs | 4.5 | Seven real trade-off ADRs, one documented correction, README drift is the only blemish |
| Correctness and reliability | 2.5 | Dual write, no consumer error handling, non-idempotent notification consumer, no past-booking guard |
| Security | 3.5 | RS256, token-derived identity, role in the filter chain, all tested; no `iss`/`aud`/`kid`, weak patient password rule |
| Maintainability and code quality | 3.0 | Consistent layering, but heavy copy-paste across services, anemic domain, no lint or coverage |
| Scalability | 3.0 | Stateless services and DB-level concurrency control; unkeyed Kafka records, no pagination |
| Extensibility | 3.0 | Hand-rolled Kafka factories and duplicated event classes raise the cost of each new event |
| Testing | 4.0 | Testcontainers with real Kafka, concurrency test, edge-drift test; no contract or frontend tests |
| DevOps and CI | 3.0 | Multi-stage images and matrix CI; no healthchecks, root containers, two suites skipped in CI |
| Frontend | 2.5 | Typed and tidy, but untested, not in CI, and missing the inactivity timeout ADR-004 promises |
| Data protection (DSGVO) | 1.5 | Health data and PII copied across three services with no minimization, deletion, or retention story |
| **Overall** | **3.3** | Senior-level reasoning, mid-level operational maturity |

As a portfolio piece for a senior engineer in Germany it currently reads as "strong upper mid-level, senior in progress". The five fixes in section 7 would move it to a convincing senior portfolio.

---

## 2. What was reviewed

| Component | Main LOC | Test LOC | Tests |
|---|---|---|---|
| api-gateway | 252 | 71 | 2 (one parameterized) |
| appointment-service | 1157 | 1596 | 38 |
| doctor-service | 1309 | 1441 | 58 |
| notification-service | 367 | 379 | 11 |
| patient-service | 704 | 653 | 23 |
| frontend (TS/TSX) | 1503 | 0 | 0 |

143 commits and 37 merged pull requests between 2026-06-15 and 2026-08-23. Stack: Java 21, Spring Boot 3.5.16, Spring Cloud Gateway MVC, Spring Security OAuth2 Resource Server, Spring Kafka, PostgreSQL 16, MapStruct, Lombok, React 19 with Vite and TypeScript.

---

## 3. ADR assessment

Each ADR was checked on two axes: is the reasoning sound, and does the code actually do what the ADR says.

### ADR-001 Microservices over monolith

Reasoning: honest. It states that a modular monolith would be the conventional choice and that microservices were picked for demonstration value. That candour is the right tone.

Gaps: the context asserts "scalability across regions, low latency, high throughput" as key requirements but nothing in the repo measures or targets any of them. More importantly, the ADR never explains the *boundaries* it chose. Why is availability owned by appointment-service and not doctor-service? Why is notification a separate service when it only writes a row? A bounded-context sketch would make this the decision it claims to be. The listed negatives also miss the one that actually bit the codebase: five copies of the same infrastructure code (section 4.3).

### ADR-002 Kafka as event broker

Reasoning: sound, and the replay property is genuinely used (consumers hydrate from the earliest offset).

Gaps: the ADR does not decide on serialization or contracts. The code uses untyped JSON with no schema registry, no versioning, and separate hand-copied event classes in every producer and consumer. Records are published without a message key, so per-aggregate ordering is not guaranteed once a topic has more than one partition. Both are Kafka decisions that belong in this ADR.

### ADR-003 Database per service

Implemented exactly as described: four PostgreSQL instances, no cross-service SQL.

Gap: a database-per-service decision without a schema migration strategy is half a decision. Every service runs `ddl-auto: update`, and the one hand-written piece of DDL (the partial unique index) lives in a `schema-postgresql.sql` file that Boot executes on every start. Flyway or Liquibase is table stakes in German enterprise Java shops and its absence will be noticed.

### ADR-004 JWT authentication

This is the best document in the repository. The decision is specific (RS256, shared key pair, role claim as discriminator, hybrid validation, declarative `hasRole` in one place per service), and the Correction section documents that the first implementation diverged from the decision, explains exactly how, fixes it, and adds regression tests. That is senior behaviour and interviewers will notice it.

Gaps, in order of importance:

- The README contradicts the corrected ADR in three places (lines 100-101, 216-217, 370-372 still say gateway validation is not enabled). Documentation drift is precisely what the correction was about, so this should be fixed.
- Tokens carry only `sub`, `role`, `exp`. Without `iss`, `aud`, or a `kid` header there is no key-rotation path and no way to scope a token to this system.
- The ADR promises a 15-minute client-side inactivity timeout. The frontend does not implement one.
- The DOCTOR role is issued and enforced (`anyRequest().hasRole("DOCTOR")`) but no endpoint in doctor-service requires it. A test comment admits this. The role is currently vestigial.
- The patient registration password has no minimum length while the doctor one requires eight characters.

### ADR-005 Cross-service validation via read-model

Reasoning: well argued, including the consistency-window analysis. Implemented as described, with upsert-by-id idempotency and an integration test against a real broker.

Gaps: only "registered" events exist. There is no update or delete path, so the read-model can never learn that a doctor changed opening hours or that a patient was deleted. The ADR should state the projection's lifecycle. Operationally, if `appointment_db` is restored from an older backup while consumer offsets are already committed, the read-model will be silently incomplete and nothing re-hydrates it. A re-hydration procedure (reset the group's offsets) belongs in the runbook.

### ADR-006 Static service discovery

Pragmatic YAGNI with a clear revisit trigger. Matches the code (environment-variable URIs). Nothing to add.

### ADR-007 Correlation ID propagation

Well argued, especially the observation that the boundary that matters here is the Kafka hop. Implemented on both boundaries with fallbacks. Minor gap: the gateway's CORS config does not expose `X-Correlation-Id` to the browser, so the frontend cannot surface it in an error message for support. The ADR is candid that this is log correlation, not tracing.

### Missing ADRs

A reader will look for decisions on: schema migrations; event contract ownership and versioning (shared module versus copied classes); repository and build structure (five independent Maven projects with no parent, no BOM); time-zone handling; personal-data handling; frontend architecture. The README's testing section could also be an ADR.

---

## 4. Findings by quality attribute

Severity: High means it undermines a claim the README makes or can lose data; Medium means it will fail under realistic load or ops conditions; Low means a quality or hygiene issue.

### 4.1 Correctness and reliability

**F1 (High): Dual write without an outbox.**
`AuthService.register`, `DoctorAuthService.register`, `AppointmentService.bookAppointment` and `cancelAppointment` all commit to the database and then call `KafkaTemplate.send`. The send is asynchronous and its future is never awaited, and there is no transaction spanning both writes. If the broker is reachable when metadata is cached but the delivery later fails, the HTTP call returns 201 and the event is lost. Concrete consequence: a patient row exists in `patient_db` but never reaches the appointment read-model, every booking for that patient returns 404 forever, and re-registering returns 409. The README's failure table says "Kafka downtime: booking and registration fail explicitly, no silent data loss". That holds only for a cold producer that times out on metadata; it is not true in general. This is the first question an interviewer asks about an event-driven system. Fix: a transactional outbox table written in the same transaction as the aggregate, published by a poller or Debezium, and an ADR-008 documenting it.

**F2 (Medium): A malformed message blocks a consumer forever.**
The consumers use a plain `JsonDeserializer` with no `ErrorHandlingDeserializer`. A deserialization failure is thrown from `poll()`, so the container retries the same record indefinitely and the partition never advances. One bad `patient.registered` message stops read-model projection for the whole system. ADR-005 defers retry and DLQ to Phase 3, but this particular failure mode is a two-line configuration fix and should not wait.

**F3 (Medium): Notification consumer is not idempotent.**
Kafka delivers at least once. `Notification` has no unique constraint on `eventId` (the field is not even persisted), so a rebalance or consumer restart produces duplicate notification rows. The README already restricts the idempotency claim to the read-model consumer, which is honest, but this is the cheapest fix in the repo.

**F4 (Medium): Booking in the past is allowed.**
`bookAppointment` checks slot alignment and opening hours but never compares the requested time with now. Availability hides past slots for display only. A direct API call can book yesterday's 10:00.

**F5 (Medium): Time zones are undefined.**
Every timestamp is a `LocalDateTime` with no zone. `LocalDate.now()` and `LocalDateTime.now()` use the JVM default (UTC inside the containers), while the browser computes "today" from UTC via `toISOString()` and displays raw slot strings. Around midnight and across DST changes the "past slot" filter and the "today" date disagree with a German user's clock. The service also cannot inject a `Clock`, which the test file itself flags.

**F6 (Low): Cancellation has no state machine.**
Cancelling an already-cancelled appointment succeeds and re-emits `appointment.cancelled`. Cancelling a past appointment is allowed. A unit test documents this as "current behaviour", which is honest, but a `cancel()` method on the entity with a guard is the natural home for the rule.

**F7 (Low): The DOCTOR role protects nothing.**
See ADR-004 above. Either add a doctor-scoped endpoint (update opening hours would also close the read-model update gap) or state in the README that doctor login is groundwork only.

### 4.2 Security

Strengths: RS256 with the private key gitignored and mounted read-only; identity taken from `sub`, never from the body; role enforced in the filter chain and proven by slice tests, a full-stack test with a real signed DOCTOR token, and a gateway drift test; BCrypt; RFC 9457 responses that never leak causes; explicit CORS with credentials off; tokens in `sessionStorage` rather than `localStorage`.

Gaps:

- No `iss`, `aud`, `kid`, or `jti` claims, so no rotation, no audience scoping, no revocation hook.
- Patient password rule is `@NotBlank` only.
- No rate limiting or lockout on the four login and register endpoints, and the gateway has no rate limiter.
- `management.endpoints.web.exposure.include` lists `beans` and `mappings` in every service. They are role-protected and not routed by the gateway, but they are production-noise in a config that otherwise reads as production-shaped.
- `show-sql: true` and `ddl-auto: update` are on in the main profile.
- Containers run as root, with no `USER` directive, no read-only filesystem, and no healthcheck.

### 4.3 Maintainability and code quality

Strengths: identical package layout in every service (controller, service, repository, domain, dto, mapper, event, exception, security, filter); constructor injection throughout; MapStruct instead of hand mapping; typed exceptions mapped to ProblemDetail in one advice per service; good inline comments that explain *why* (the `JwtDecoderConfig` split, the `/error` permit, the seeder ordering); a real value-object fix with a cleanup script for the opening-hours duplicate bug.

Gaps:

- **Copy-paste across services.** `CorrelationIdFilter` exists five times, `RsaKeyProperties` and `JwtDecoderConfig` four times, `GlobalExceptionHandler` four times with near-identical bodies, the role converter three times, the token provider twice. Spring Boot's version is pinned five times with no parent POM or BOM. Microservice independence justifies *some* duplication, but a senior engineer either extracts a small `healthtech-common-web` starter plus a root aggregator POM, or writes the ADR that says why not. Neither is here.
- **Anemic domain.** Entities are Lombok data bags. All invariants (alignment, opening hours, slot grid, ownership, status transitions) live in `AppointmentService`, which also does persistence, event assembly, and correlation-id plumbing. A `SlotGrid` or `AvailabilityCalculator` value object and an `AppointmentEventPublisher` port would separate the three concerns and make the past-slot filter testable.
- **Misleading name.** `DoctorRepository.findWithDetailsById` is a derived query; Spring Data ignores "WithDetails", so it is a plain `findById` with no fetch join. The name promises eager loading that `@BatchSize` inside a read-only transaction is quietly providing instead.
- **Leaky DTO.** `AppointmentResponse` imports `jakarta.persistence.*` and `@CreationTimestamp` (unused). A linter would catch it; there is none.
- **Hand-rolled Kafka configuration.** `KafkaConsumerConfig` reads `spring.kafka.bootstrap-servers` through `@Value`, bypassing Boot's `KafkaConnectionDetails`. Two integration tests carry comments explaining the workaround they need because of it. Boot's auto-configured factories with `spring.kafka.consumer.*` properties would delete most of both classes.
- **Duplicated business rule.** The 30-minute slot is hard-coded in `AppointmentService` and again in the frontend's `HALF_HOUR_TIMES`. Topic names are string literals in producers, consumers, and tests.
- **No quality gates.** No Checkstyle, Spotless, or Error Prone; no JaCoCo; no Dependabot or OWASP dependency check; the frontend's `oxlint` is not run in CI.
- **Stale documentation.** README contradicts ADR-004 (three places); the compose header says "PostgreSQL x3" while there are four; `version: '3.8'` is an obsolete compose key.

### 4.4 Scalability

Strengths: stateless services with JWT, so any service can scale out; the partial unique index puts double-booking control in the database, where it scales correctly; consumer groups per service.

Gaps:

- Kafka records are published without keys. Once `patient.registered` or `appointment.booked` has more than one partition, events for the same aggregate can be consumed out of order. Key by `patientId`, `doctorId`, or `appointmentId`.
- `GET /api/doctors` and `GET /api/appointments` return unbounded lists; no `Pageable` anywhere.
- The gateway (blocking MVC flavour) has no timeouts, retries, circuit breaker, or rate limiting configured for proxied calls.
- Compose has no healthchecks and `depends_on` has no conditions, so a cold `docker-compose up --build` races Kafka and Postgres readiness, and with no `restart` policy a service that loses the race stays down. For a portfolio project, this is the reviewer's first five minutes.
- No metrics. Actuator is present but Micrometer and a Prometheus endpoint are not wired.

### 4.5 Extensibility

Adding a doctor-scoped feature is cheap: security is ready. Adding a public endpoint is deliberate: gateway list plus test. Adding a new event is expensive: a class in the producer, a copy in each consumer, a consumer factory bean, a listener, and manual correlation-id plumbing on both sides, with nothing but convention keeping the two copies compatible. An OpenAPI spec (springdoc) is absent, so the frontend re-declares every enum and DTO by hand; a generated client would remove that drift.

### 4.6 Testing

Strengths: the three-layer strategy is real and the README explains it well. The concurrency test fires ten simultaneous bookings at one slot and asserts exactly one 201 and nine 409s. Security is tested at every layer: untrusted key gives 401, DOCTOR token gives 403 via MockMvc and via a real signed token through the full filter chain, and the gateway test fails if a public path disappears from the edge list. `ReadModelProjectionIntegrationTest` and `NotificationConsumerIntegrationTest` use a real `ConfluentKafkaContainer`. Several tests pin "current behaviour" with a comment saying so, which is honest engineering.

Gaps: no contract tests between event producers and consumers; no frontend tests; CI excludes `DoctorServiceApplicationTests` and `PatientServiceApplicationTests` without a documented reason, even though it generates keys for exactly those two modules; no coverage measurement; the notification test resources hardcode an H2 dialect that the integration test then overrides back to Postgres; polling helpers are hand-rolled where Awaitility is the idiom.

### 4.7 Frontend

A thin, typed React client with a single `request()` wrapper, ProblemDetail-aware errors, separate patient and doctor auth contexts, and a good 409 recovery path on the booking page. It is what a backend-focused portfolio needs and no more.

Gaps: zero tests; not in CI; not containerized; no inactivity timer despite ADR-004; 12-hour AM/PM time formatting on a German product; `todayIsoDate()` is UTC; the module-level `navigate` bridge is a workaround for calling the router from the API layer.

### 4.8 Developer experience and operations

Strengths: multi-stage Dockerfiles with a cached dependency layer; images pushed to GHCR on main; matrix CI per service with Maven caching; two end-to-end smoke scripts; idempotent seeders with a documented demo password; Kafka UI in compose.

Gaps: root containers, no healthchecks, no restart policies, no `.env` file, plaintext credentials in compose (acceptable locally, but a comment saying so would help), no Makefile or task runner for the multi-project checkout, no OpenAPI, no metrics, README drift.

### 4.9 Data protection

Not addressed anywhere, which for a German health-tech project is the most visible gap to a local reviewer. Specifically:

- `AppointmentBooked` carries `patientName` and `patientEmail` into notification-service; the read-model copies `email`, `firstName`, `lastName` into appointment-service. Health-context data (appointment type, free-text notes) is Art. 9 special-category data under the DSGVO. There is no data-minimization argument for why each copy exists.
- No deletion path: no `PatientDeleted` event, no way to honour an erasure request across four databases.
- No retention policy, no encryption-at-rest discussion, no audit log of who accessed what.
- Free-text `notes` are stored unbounded in meaning if not in length.

None of this needs to be implemented to be a senior portfolio, but it must be *reasoned about*: an ADR that names the categories of data, justifies each cross-service copy, and lists what production would add.

---

## 5. What is genuinely good

Reviewers skim for red flags, so it is worth naming the green ones explicitly:

- Seven ADRs that argue trade-offs instead of listing technologies, including a self-correction.
- The partial unique index for race-safe booking, verified by a concurrency test.
- Identity from the token, role in the filter chain, and tests at three layers proving both.
- Event-carried state transfer done correctly: idempotent upsert, hydration from the earliest offset, tested against a real broker.
- Correlation IDs across the Kafka hop, with the fallback semantics thought through.
- RFC 9457 everywhere, with field-level validation errors and no leaked stack traces.
- Local-market detail: `PRIVATE` and `STATUTORY` insurance types, the Arztekammer note, German demo addresses.
- Code comments that explain intent, and tests that state when they are pinning a known limitation.

---

## 6. Portfolio assessment for a senior engineer in Germany

**What it demonstrates well:** distributed-systems reasoning, the ability to write and revise an ADR, security design with tests to back it, and a mainstream stack that German employers actually run (Spring Boot, Kafka, Postgres, Docker, GitHub Actions). The ADR-004 correction alone is a better interview artifact than most complete projects.

**What a senior reviewer will probe, and where the current answers are weak:**

1. "What happens if Kafka is unavailable after the database commit?" Today the honest answer is "we lose the event and the user gets a 201". This is the standard event-driven interview question and the outbox pattern is the standard answer.
2. "How do you evolve a schema?" There is no migration tool.
3. "How do you handle a poison message?" Today it stops the consumer.
4. "Why is this code in five places?" There is no ADR and no shared module.
5. "How does this system handle patient data under the DSGVO?" There is no answer in the repo.
6. "Can I run it?" Cold-start races in Compose may make the first attempt fail.

**Signals of AI-assisted development.** The `docs/specs` folder contains a generation brief for doctor-service, and the README lists AI-assisted workflows as a goal. This is fine and increasingly normal, but expect a reviewer to ask you to defend any file in the repo without notes. Keep the specs (they show process); be ready to explain every decision in them.

**Verdict.** Senior-level architectural judgement, mid-level operational depth. The project would land well for a mid-to-senior backend role today. With the five items below it becomes a credible senior portfolio, because each one converts a documented "Phase 3" promise into demonstrated engineering.

---

## 7. Prioritized recommendations

Ordered by credibility gained per hour of work.

1. **Transactional outbox, plus ADR-008.** Write an `outbox` row in the same transaction as the aggregate in all four publishing paths; publish from a scheduled poller (or Debezium later). Await the send in tests. Update the README failure table to say what is now true.
2. **Consumer resilience, plus ADR-009.** Wrap deserializers in `ErrorHandlingDeserializer`; configure `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer` and back-off; persist `eventId` with a unique index in notification-service and ignore duplicates. Add a test that a malformed record lands in the DLT and the partition advances.
3. **Flyway per service.** `V1__init.sql` per service including the partial unique index; set `ddl-auto: validate`; delete `schema-postgresql.sql` and the H2 dialect hack.
4. **Build hygiene.** Root aggregator POM with a shared BOM; extract `common-web` (correlation filter, problem handler, RSA properties, role converter) or write the ADR that rejects it. Add Spotless, Error Prone or Checkstyle, JaCoCo with a threshold, Dependabot, frontend lint and a handful of Vitest tests in CI. Un-skip the two application tests or document why they are skipped.
5. **Data-protection ADR.** Name the data categories, justify or remove `patientEmail` and `patientName` from `AppointmentBooked`, add a `PatientDeleted` event consumed by the read-model, state retention and encryption for production.
6. **Smaller correctness fixes.** Reject bookings in the past; guard cancellation with a status transition on the entity; inject a `Clock`; store instants as `OffsetDateTime` or `Instant` with `Europe/Berlin` at the edge; key every Kafka record by aggregate id; add `iss`, `aud`, and a `kid`; add a minimum patient password length; paginate doctor and appointment lists.
7. **Run-it-first-time fixes.** Compose healthchecks with `depends_on: condition: service_healthy`, `restart: on-failure`, a non-root `USER` in every Dockerfile, correct the README's gateway statements and the compose header, drop `show-sql` and the `beans`/`mappings` exposure from the main profile.
8. **Nice to have.** springdoc OpenAPI with a generated TypeScript client; Micrometer plus Prometheus; the 15-minute inactivity timer and 24-hour times in the frontend; one doctor-scoped endpoint (update opening hours) with a `DoctorUpdated` event so the DOCTOR role and the read-model update path both become real.
