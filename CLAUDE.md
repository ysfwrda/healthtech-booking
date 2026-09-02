# Subagents

This repo defines custom Claude Code subagents in `.claude/agents/`. Use the
relevant one proactively at these trigger points, not only when explicitly
asked:

- After adding or modifying any `*Controller.java`, a request/response DTO
  under `dto/`, or a `SecurityConfig`/JWT-related class: run `code-reviewer`
  and `endpoint-tester` before considering the change done.
- After writing or editing an ADR under `docs/adr/`: run
  `adr-consistency-checker` against that ADR.
- When reviewing a diff, PR, or branch against a separate written spec
  document: run `spec-to-diff-reviewer`.
- After changing `docker-compose.yml`, any `application.yaml`, or any
  `pom.xml`: run `config-dependency-auditor`.

Each agent's file documents its own scope boundary — don't ask one to do
another's job. In particular: `spec-to-diff-reviewer` checks fidelity to a
spec only and does not judge quality or security; `code-reviewer` judges
quality/security/consistency using its own judgment, not fidelity to any
document; `adr-consistency-checker` checks the codebase against an ADR's
claims (trusts the code, checks the doc), which is the opposite direction
from how `code-reviewer` uses ADRs (trusts the ADR, checks the code).
