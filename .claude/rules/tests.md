---
paths:
  - "**/src/test/**/*.java"
---

# Java tests

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
- An outbox test change goes into all three services' copies of that test.
