---
paths:
  - "**/src/test/**/*.java"
---

# Java tests

## Which tests a change needs

Each new or changed behavior has unit tests for its happy path and for
each failure path:
- validation errors, including the values on both sides of each limit
  (`@Size(min = 8)` on the doctor password: test 7 and 8)
- not found, conflict and invalid state
- 401, 403 and ownership violations
- failures of a dependency it calls

For a controller, the `@WebMvcTest` slice test is its unit test. Then add
tests at every other layer the change reaches:

| The change touches | Also test with |
|---|---|
| Only business rules, services, mappers or a consumer's handling logic | Nothing more: unit tests (Mockito, no Spring) cover it |
| Controllers, request validation, security rules, error responses | `@WebMvcTest` slice test |
| Repositories, queries, constraints, locking, transactions | Testcontainers integration test |
| Kafka producers, consumers, the outbox, the read models | Testcontainers integration test with a real broker where delivery is the point |
| Gateway routing or edge auth | `GatewaySecurityTest`-style test in api-gateway |

For a bug, a test reproduces it first and passes after the fix. When the
fix is a guard (lock, constraint, validation), show the test fails with
the guard removed. For example,
`createAppointment_concurrentUsers_returnStatus409` must fail without the
`ux_active_appointment` unique index.

## How to write them

- Mark the structure of every new or changed test with `// Arrange`,
  `// Act` and `// Assert` comments. When the call is the assertion
  (`assertThrows`, `mockMvc.perform(...).andExpect(...)`), use one
  `// Act & Assert` marker. Leave untouched tests that lack markers alone.
- One claim per test. Split a test that checks two behaviors, and move
  shared setup into helpers.
- Class-level comments: at most two lines.
- Slice tests (`@WebMvcTest`) `@Import(SecurityConfig.class)` to get the
  real authorization rules and declare `@MockitoBean JwtDecoder`, so they
  need no key material. Don't load `JwtDecoderConfig` in a slice test.
- In patient, doctor and appointment services, a new `@SpringBootTest` that
  doesn't test outbox delivery sets `outbox.relay.fixed-delay-ms=3600000`,
  so the relay doesn't poll Kafka every second during the test.
- An outbox test change goes into all three services' copies of that test,
  adapted to each service.
