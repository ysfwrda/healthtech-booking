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

1. **The behavior is proven.** For a bug, a test reproduces it first and
   passes after the fix. When the fix is a guard (lock, constraint,
   validation), show the test fails with the guard removed, as #51 did.
   For a feature, the new behavior and its failure paths are tested at the
   right layer: unit for rules and services, `@WebMvcTest` for the
   web/security contract, Testcontainers for persistence, Kafka, locking and
   the outbox.
2. **Every affected suite passes**, integration tests included. The cloud
   session hook provides Docker and keys. If a layer still can't run, the
   reason must come from the environment, and the PR says so.
3. **`verify` has run** and the subagents it requires have run. Confirmed
   findings are fixed; findings you reject are explained in the PR.
4. **Endpoint changes are checked on the live stack**
   (`docker compose up -d --build`, then `endpoint-tester` or the scripts),
   not only in tests.
5. **Everything that describes the behavior matches it.** That covers
   OpenAPI annotations, README, ADRs, `scripts/test-flow.sh`,
   `scripts/gateway-security-smoke-test.sh` and the frontend when it calls
   the changed endpoint.
6. **Duplicated code is changed in every copy** (see CLAUDE.md), and
   `scripts/check-outbox-drift.sh` passes.
7. **You have re-read the full diff** as a reviewer would. No debug
   output, dead code, unused imports or changes the task didn't need.
8. **The PR is open and green.** It has a `type(scope): subject` title and
   a body with `## Summary`, `## Testing` and `Closes #N`. Testing lists
   what ran, with test counts, and what didn't run, and why. CI is green on
   the latest commit and every review thread has a reply or a fix.

Until all eight hold, the task isn't done. Don't report it as done, and
don't hand it back unless one of the "Ask first" points is blocking.
