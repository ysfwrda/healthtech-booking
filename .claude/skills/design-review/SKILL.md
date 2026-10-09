---
name: design-review
description: Write a design note for a task and get it reviewed by design-reviewer before implementing. Use for a task without an approved spec that adds a new endpoint or a new event (even within one service), or behavior spanning services. Bug fixes and other changes contained in one service skip it.
---

# Design review before implementing

## 1. Decide whether it applies

It applies when the task has no approved spec and does either of these:
- adds a new endpoint or a new Kafka event, even within one service
- adds behavior that spans services

Skip it for bug fixes and other changes contained in one service, and say
so in the PR.

An approved spec is a document in `docs/specs/` that the user wrote or
approved for this task, or a design note that passed this skill for this
task. A note or spec written for an earlier task isn't one, even if it
covers the same area.

## 2. Write the design note

Read the ADRs the task touches and the affected code first. Then create a
branch and write `docs/specs/<short-name>.md`:

```markdown
# Design: <title>

Status: draft

## Task
<the issue body or the user's words, copied, not paraphrased>

## Approach
<what changes in which service, in a few paragraphs>

## Alternatives considered
<each with one line on why not>

## Fits the codebase
<for each concern, the existing mechanism it uses (outbox, exceptions,
mappers, read models, package layout); any new one, and why the existing
one can't do the job>

## Hard-to-reverse decisions
<data ownership, data model, event payloads, API shapes: each with its
justification and "ADR planned: yes (which) / no (why not)">

## Contracts
<changed endpoints, events, schema; "none" if none>

## Failure modes
<Kafka down, duplicates, concurrency, partial failure: how each is handled>

## Test plan
<per behavior: happy and failure paths (definition of done, item 1), and
the layer for each, as `.claude/rules/tests.md` requires>

## Ask first
<any API contract, schema/event, security or dependency change, and any
departure from a CLAUDE.md rule or an ADR; "none">
```

Keep it to what the implementation needs, about a page. Commit the note.

## 3. Get it reviewed

Start `design-reviewer` with exactly this:

```text
Task (verbatim from <issue #N | the user's request>):
<the issue body or the user's words, copied, not paraphrased>

Design note: docs/specs/<short-name>.md in <repo path>.
```

Add nothing else: no summary, no reasons, no focus hints.

## 4. Handle the findings

- **blocking (factual)**: start one `finding-verifier` per finding with
  the task, the design note path and the finding as written. Revise the
  note for CONFIRMED or UNCERTAIN findings. List REFUTED ones in the PR
  with the verifier's evidence.
- **blocking (trade-off)**: keep it for the hand-back in step 5.
- **A hidden "Ask first" change**: add it to the note's "Ask first"
  section, even if the finding is only optional.
- **optional**: fold in the plainly right ones; list the rest in the PR.

After revising, commit and start a fresh `design-reviewer` on the revised
note with the same two inputs. Repeat until a review has no confirmed
blocking (factual) finding. Stop and hand back if a finding you already
revised for comes back and the verifier confirms it, or if two revisions
in a row still have confirmed blocking findings.

## 5. Ask the user once, with the reviewed note

If the note's "Ask first" section isn't "none", or the last review has
blocking trade-offs, hand back now, once, with:
- the note's path
- each "Ask first" item, as the note describes it
- the reviewer's "Decisions for the user" block

Wait for the answer. Revise the note to match it and, if the design
changed, start a fresh `design-reviewer` as in step 4. Hand back again
only for a new trade-off or "Ask first" item that this review raises.

The user's approval covers the "Ask first" items exactly as the note
describes them; record it under "Ask first" (`approved by the user,
<date>`). A change the note doesn't describe still needs asking.

If there is nothing to ask, go on without handing back.

## 6. Implement against the note

Set the note's status and commit it:

```markdown
Status: approved for <issue #N, or the user's request of DATE>: passed
design-reviewer[; the user approved its "Ask first" items on DATE].
Records the design at approval; the code, README and ADRs are the
current state.
```

The approved note is the spec. When `verify` runs, it gives
`spec-to-diff-reviewer` this note. If the implementation has to depart
from it, update the note in the same PR and say why in the PR Summary.
