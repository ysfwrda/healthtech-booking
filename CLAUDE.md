# Subagents

This repo defines custom Claude Code subagents in `.claude/agents/`. Use the
relevant one proactively at these trigger points, not only when explicitly
asked:

- After any change outside `docs/` and Markdown files: run `code-reviewer`.
- After adding or modifying any `*Controller.java`, a request/response DTO
  under `dto/`, or a `SecurityConfig`/JWT-related class: also run
  `endpoint-tester` before considering the change done.
- After writing or editing an ADR under `docs/adr/`: run
  `adr-consistency-checker` against that ADR.
- When reviewing a diff, PR, or branch against a separate written spec
  document: run `spec-to-diff-reviewer`.
- After changing `docker-compose.yml` or `docker-compose.images.yml`, any
  `application.yaml`, or any `pom.xml`: run `config-dependency-auditor`.

A change is never reviewed by its author. Give each agent only the task
(verbatim) and the diff or document to check, never your reasoning, your
summary or the PR description. The `verify` skill has the exact prompt.

Each agent's file documents its own scope boundary — don't ask one to do
another's job. In particular: `spec-to-diff-reviewer` checks fidelity to a
spec only and does not judge quality or security; `code-reviewer` judges
quality/security/consistency using its own judgment, not fidelity to any
document; `adr-consistency-checker` checks the codebase against an ADR's
claims (trusts the code, checks the doc), which is the opposite direction
from how `code-reviewer` uses ADRs (trusts the ADR, checks the code).

# Definition of done

`.claude/rules/definition-of-done.md` says what to decide alone, what to ask
about first, and when a task counts as done. Merging is always the user's
call.

# Build and test

- When a change is finished, commit it, then run the `verify` skill before
  opening the PR. It runs the checks below for the files you touched, sends
  the committed diff to the reviewing agents and writes the PR's Testing
  section.
- Cloud sessions run `.claude/hooks/session-start.sh`, which starts Docker,
  generates `keys/`, and downloads Maven and npm dependencies.
- There is no root pom. Run `mvn -B test` inside each service you touched.
- The Spring-context tests in patient-service and doctor-service
  (`*IntegrationTest`, `*ApplicationTests`) need `keys/private.pem`; generate
  the pair as in the README's "JWT Keys" step. Testcontainers needs Docker.
- Frontend: `npm run lint && npm run build` in `frontend/`.
- End to end against a running stack: `scripts/test-flow.sh` and
  `scripts/gateway-security-smoke-test.sh`. Update both when request shapes
  or demo credentials change.
- In the PR body, say which test layers you did not run, and why.

# Conventions

- Java test conventions (Arrange/Act/Assert markers, slice and integration
  test setup) are in `.claude/rules/tests.md`, loaded when working on tests.
- Comments describe what the code does today. Don't claim extensibility or
  guarantees the code doesn't enforce, and keep them short.
- Some code is duplicated per service on purpose: the outbox
  (entity, repository, relay, pruning job, `DomainEventPublisher` /
  `OutboxEventWriter`) in patient, doctor and appointment services (ADR-008),
  plus `OpenApiErrorCustomizer`, `SecurityConfig`, `JwtDecoderConfig`,
  `RsaKeyProperties`, `GlobalExceptionHandler`, `CorrelationIdFilter` and
  each `pom.xml`. A fix to one copy goes into every copy in the same PR,
  tests included.
  `scripts/check-outbox-drift.sh` (also run in CI) fails when the copies
  of the `outbox/` package or `DomainEventPublisher` differ. Nothing checks
  the other copies or the outbox tests, which are adapted per service.
- Domain events are published through `DomainEventPublisher` inside the
  caller's `@Transactional` method, never with `KafkaTemplate` directly
  (the demo `DoctorSeeder` is the one existing exception).
- When behavior changes, update the README section and any ADR that
  describes it in the same PR. ADRs must not reference ADRs or specs that
  don't exist in the repo; specs live in `docs/specs/`.
- PR titles: `type(scope): subject`, with type one of feat, fix, docs, test, refactor, infra, ci, chore.
  Branch commits may use the same prefix or a plain imperative subject
  ("Reject slots in the past"); recent PRs use the plain form.
- PR body: `## Summary`, `## Testing` (including what wasn't run), then
  `Closes #N` when there is an issue.
