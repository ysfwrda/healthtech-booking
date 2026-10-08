---
name: verify
description: Run the checks this repo needs for the current change (per-service Maven tests, frontend lint/build, outbox drift check, and the subagents CLAUDE.md requires for the files touched), then write the PR's Testing section. Use before committing a finished change or opening a PR, or when the user asks to verify, test, or check a change.
---

# Verify the current change

## 1. Find the change

List changed files against the base, including uncommitted work:

```bash
git fetch -q origin main
git diff --name-only origin/main...HEAD
git diff --name-only HEAD
git ls-files --others --exclude-standard
```

A user-supplied base branch or PR replaces `origin/main`.

## 2. Run the local checks

Run every check that applies and record each result, including the ones you
could not run and why.

| Changed path | Check |
|---|---|
| `<service>/**` (api-gateway, appointment-service, doctor-service, notification-service, patient-service) | `mvn -B test` in that service's directory. There is no root pom. |
| `*/outbox/**`, or any of the three services that hold an outbox copy | `scripts/check-outbox-drift.sh` |
| `frontend/**` | `npm run lint && npm run build` in `frontend/` |
| `scripts/*.sh` | `bash -n` on each changed script |
| `.github/workflows/*.yml` | parse the YAML |

Prerequisites. The cloud session-start hook sets these up; locally, check them:
- The Spring-context tests in patient-service and doctor-service need
  `keys/private.pem` matching `keys/public.pem` (README, "JWT Keys").
- `*IntegrationTest` and most `*ApplicationTests` use Testcontainers and need
  a running Docker daemon (`docker info`).

If a prerequisite is missing, still run the tests that don't need it:
`mvn -B test -Dtest='!*IntegrationTest,!*ApplicationTests'`. Report the rest
as not run; don't call it a pass.

A failing test is a finding to fix or report, never something to skip.

## 3. Get an independent review from the subagents

The author of a change doesn't review it. Review happens in the repo's
subagents, which start without this session's context. Keep it that way.

**Which agents.** Apply the trigger list in CLAUDE.md to the changed files:
- Any change outside `docs/` and `*.md`: `code-reviewer`.
- `*Controller.java`, `dto/**`, `SecurityConfig`, or JWT classes: also
  `endpoint-tester`. It needs the stack running
  (`docker compose up -d --build`). If you can't start it, say so.
- `docs/adr/**`: `adr-consistency-checker` on each changed ADR.
- `docker-compose*.yml`, `application.yaml`, or `pom.xml`:
  `config-dependency-auditor`.
- A spec document is part of the task: `spec-to-diff-reviewer`.

**What each agent gets: the task and the diff, nothing else.** Commit
first, then send exactly this:

```text
Task (verbatim from <issue #N | the user's request>):
<the issue body or the user's words, copied, not paraphrased>

Change: `git diff origin/main...HEAD` on branch <branch> in <repo path>.
```

`adr-consistency-checker` gets the ADR path instead of the diff, and
`spec-to-diff-reviewer` also gets the spec path.

Leave out everything that comes from you as the author:
- your summary of the change or the approach
- why it's correct, or what you considered and rejected
- what you already tested or checked
- what to focus on or skip
- the PR description, your draft of it, and earlier review findings

Use the named repo agents. Never use a `fork` subagent or any agent that
inherits this conversation, and don't count your own reading of the diff
or an in-session review skill as the review.

Run independent agents in parallel.

**Handling findings.** Fix confirmed findings. For a finding you reject,
put it in the PR with the code evidence that refutes it, so the user
sees the disagreement. After fixing, start a fresh agent on the updated
diff with the same two inputs. Don't continue the earlier agent with
SendMessage, since that passes it your arguments.

## 4. Check the conventions CLAUDE.md lists

Read the diff once for the conventions that tests don't catch:
- every new or changed behavior has happy-path and failure-path tests at
  each layer item 1 of `.claude/rules/definition-of-done.md` requires; add
  the missing ones
- new tests use `// Arrange`, `// Act`, `// Assert`
- comments state only what the code does
- changed behavior is reflected in the README and the ADRs
- scripts and README examples still match the request shapes

## 5. Report

End with a block ready to paste as the PR's Testing section:

```markdown
## Testing
- <service>: `mvn -B test`: N tests, 0 failures
- Frontend: lint and build pass
- Outbox copies: identical
- Agents: code-reviewer (no findings / fixed X), endpoint-tester (N/N cases pass)
- Not run: <check>: <reason>
```

Keep the "Not run" line even when it's empty: write "Not run: nothing".
