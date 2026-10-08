---
name: verify
description: Run the checks this repo needs for the current change (per-service Maven tests, frontend lint/build, outbox drift check, and independent review by the subagents CLAUDE.md requires for the files touched), then write the PR's Testing section. Use after committing a finished change and before opening a PR, or when the user asks to verify, test, or check a change.
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
`mvn -B test -Dtest='!*IntegrationTest,!*ApplicationTests,!OutboxPruningJobTest'`
(`OutboxPruningJobTest` also starts a Postgres container). Report the rest
as not run; don't call it a pass.

A failing test is a finding to fix or report, never something to skip.

## 3. Get an independent review from the subagents

The author of a change doesn't review it. Review happens in the repo's
subagents, which start without this session's context. Keep it that way.

### Which agents

Apply the trigger list in CLAUDE.md to the changed files:
- Any change outside `docs/` and `*.md`, and any change under `.claude/`
  including its Markdown (rules, agents, skills): `code-reviewer`, at the
  depth below.
- `*Controller.java`, `dto/**`, `SecurityConfig`, or JWT classes: also
  `endpoint-tester`. It needs the stack running
  (`docker compose up -d --build`). If you can't start it, say so.
- `docs/adr/**`: `adr-consistency-checker` on each changed ADR.
- `docker-compose.yml`, `application.yaml`, or `pom.xml`:
  `config-dependency-auditor`. (`docker-compose.images.yml` only sets
  image tags and has nothing for it to check.)
- A spec document is part of the task: `spec-to-diff-reviewer`.

### How deep: set by what the change touches

The deepest tier any changed file falls into applies to the whole change.
A file that fits no row counts as tier 2: test files, `infrastructure/`,
Dockerfiles, and build config such as plugin or version changes in a
pom. A new library in a `pom.xml` or `package.json` is tier 3.

| Tier | The change touches | `code-reviewer` first round | Final round |
|---|---|---|---|
| 1 | Only tooling: `scripts/`, `.github/`, anything under `.claude/` | One agent, no lens | None |
| 2 | Service or frontend code, or service config (`application.yaml`, compose files) | Three agents in parallel: `Lens: correctness`, `Lens: security`, `Lens: consistency` | None |
| 3 | An "Ask first" area: API contract, schema or events, security, dependencies | As tier 2 | One more full review, no lens, of the whole diff after all fixes |

### What each agent gets: the task and the diff, nothing else

Commit first, then send exactly this:

```text
Task (verbatim from <issue #N | the user's request>):
<the issue body or the user's words, copied, not paraphrased>

Change: `git diff <base>...HEAD` on branch <branch> in <repo path>.
[Lens: <correctness | security | consistency>]
```

`<base>` is `origin/main` for a first or final round, and the last
reviewed commit for a fix-only round. Add the `Lens:` line only where
the tier table says so. `adr-consistency-checker` gets the ADR path
instead of the diff, and `spec-to-diff-reviewer` also gets the spec path.

Leave out everything that comes from you as the author:
- your summary of the change or the approach
- why it's correct, or what you considered and rejected
- what you already tested or checked
- any focus or skip hint of your own (the fixed lenses are not yours)
- the PR description, your draft of it, and earlier review findings

Use the named repo agents. Never use a `fork` subagent or any agent that
inherits this conversation, and don't count your own reading of the diff
or an in-session review skill as the review.

### Verify each blocking finding before acting on it

This applies to `code-reviewer`, whose findings are judgments labelled
blocking or optional. The other agents report observed results with
evidence. These count as confirmed blocking findings without a verifier:
- a failed `endpoint-tester` case
- a Contradicted claim from `adr-consistency-checker`
- a Missing or Partially implemented requirement from
  `spec-to-diff-reviewer`
- from `config-dependency-auditor`, an unresolved placeholder, an address
  or wiring mismatch, or anything its verdict says would break the stack

Their other reports are facts, not verdicts, and count as optional:
dependency version drift, un-agreed additions to a spec, and Unverifiable
ADR claims. List them in the PR.

For every `code-reviewer` finding labelled blocking, start one
`finding-verifier` with the task, the same diff reference and the
finding copied as the reviewer wrote it. Run them in parallel. Add
nothing of your own: no rebuttal, no context.

- **CONFIRMED** or **UNCERTAIN**: fix it. You don't get to reject a
  finding the verifier couldn't refute. If you think it's wrong, hand it
  back to the user (see the stop rules in the definition of done).
- **REFUTED**: don't fix it. List it in the PR with the verifier's
  evidence. A refuted security finding (from `Lens: security`, or from the
  Security section of an unlensed review) first goes to a second, fresh
  verifier with the same inputs. It counts as refuted only if both refute
  it; otherwise fix it.

Optional findings aren't verified and never start another round. Fold
the plainly correct ones into a fix you're already making, and list the
rest in the PR.

### Re-review the fixes only

After fixing, commit and start a fresh `code-reviewer` on the fix
commits alone (`<base>` = the last reviewed commit), no lens. A fix for
another agent's finding is also re-checked by a fresh run of that agent
(`endpoint-tester` on the affected endpoints, `adr-consistency-checker`
on the ADR, and so on). Verify its
blocking findings the same way. Repeat until a fix-only round has no
confirmed blocking findings, unless a stop rule in the definition of
done applies first. Then run the tier-3 final round if the tier calls for
it. Its confirmed blocking findings are fixed and re-reviewed with
fix-only rounds as above; there is no second final round.

Never continue an earlier agent with SendMessage; that passes it your
arguments.

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
- Agents: code-reviewer (tier N; no findings / fixed X), endpoint-tester (N/N cases pass)
- Refuted findings: <finding>: <verifier's evidence> (or "none")
- Not run: <check>: <reason>
```

Keep the "Not run" line even when it's empty: write "Not run: nothing".
