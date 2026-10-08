# Definition of done

Work through a task on your own, up to an open PR with green CI. Stop and
ask only at the points listed under "Ask first". Merging is always the
user's call.

## Decide yourself, and say so in the PR

- How to implement the change, how to structure the tests, and small
  refactors inside the code you are already changing.
- Wording of comments, README sections, ADR corrections and OpenAPI
  descriptions that the change makes necessary.
- Fixes for CI failures and review comments on your own PR.

When a choice was not obvious (two reasonable designs, a behavior the issue
didn't specify, e.g. "a slot starting exactly now is accepted"), make it and
name it in the PR Summary so the reviewer can overturn it.

## Ask first

Stop and ask before any of these, even when the task seems to require it:

- **API contract**: a new or removed endpoint, or a changed request or
  response shape, status code or header that the frontend, the scripts or
  other services rely on.
- **Schema and events**: changes to any `schema-postgresql.sql`, or to a
  Kafka topic or event payload.
- **Security**: `SecurityConfig`, JWT issuing or validation, role and
  ownership checks, gateway auth or CORS.
- **Dependencies**: a new library in a `pom.xml` or `package.json`.

Also ask when the task can't be done without breaking a rule in CLAUDE.md
or an ADR.

Note bugs you find outside the task in the PR description. Don't fix
them in the same PR.

## Done means all of these hold

1. **Every behavior and path is tested.** Each new or changed behavior
   has unit tests for its happy path and for each failure path:
   - validation errors, including the values on both sides of each limit
     (`@Size(min = 8)` on the doctor password: test 7 and 8)
   - not found, conflict and invalid state
   - 401, 403 and ownership violations
   - failures of a dependency it calls

   For a controller, the `@WebMvcTest` slice test is its unit test.

   Then add tests at every other layer the change reaches:

   | The change touches | Also test with |
   |---|---|
   | Only business rules, services, mappers or a consumer's handling logic | Nothing more: unit tests (Mockito, no Spring) cover it |
   | Controllers, request validation, security rules, error responses | `@WebMvcTest` slice test |
   | Repositories, queries, constraints, locking, transactions | Testcontainers integration test |
   | Kafka producers, consumers, the outbox, the read models | Testcontainers integration test with a real broker where delivery is the point |
   | Gateway routing or edge auth | `GatewaySecurityTest`-style test in api-gateway |

   For a bug, a test reproduces it first and passes after the fix. When
   the fix is a guard (lock, constraint, validation), show the test fails
   with the guard removed. For example,
   `createAppointment_concurrentUsers_returnStatus409` must fail without the
   `ux_active_appointment` unique index. The frontend has no test setup, so
   a frontend change says in the PR that it is untested and how it was
   checked by hand. Adding a test runner is a new dependency: ask first.
2. **Every affected suite passes**, integration tests included. The cloud
   session hook provides Docker and keys. If a layer still can't run, the
   reason must come from the environment, and the PR says so.
3. **The change has been reviewed by someone other than its author.**
   `verify` has run, and the subagents it requires reviewed the change
   from the task and the diff alone, without your reasoning. Confirmed
   findings are fixed and re-reviewed by a fresh agent. Rejected findings
   are listed in the PR with the evidence.
4. **Endpoint changes are checked on the live stack**
   (`docker compose up -d --build`, then `endpoint-tester` or the scripts),
   not only in tests.
5. **Everything that describes the behavior matches it.** That covers
   OpenAPI annotations, README, ADRs, `scripts/test-flow.sh`,
   `scripts/gateway-security-smoke-test.sh` and the frontend when it calls
   the changed endpoint.
6. **Duplicated code is changed in every copy** (see CLAUDE.md).
   `scripts/check-outbox-drift.sh` must pass, but it only covers the
   `outbox/` package and `DomainEventPublisher`. Check the other copies
   (`OpenApiErrorCustomizer`, `SecurityConfig`, the poms, the outbox tests)
   yourself.
7. **You have also re-read the full diff yourself.** This is a hygiene
   pass, not the review. No debug
   output, dead code, unused imports or changes the task didn't need.
8. **The PR is open and green.** It has a `type(scope): subject` title and
   a body with `## Summary`, `## Testing`, and `Closes #N` when there is
   an issue. Testing lists
   what ran, with test counts, and what didn't run, and why. CI is green on
   the latest commit and every review thread has a reply or a fix.

Until all eight hold, the task isn't done. Don't report it as done, and
don't hand it back unless one of the "Ask first" points is blocking.
