---
name: design-review
description: Write a design note for a task and get it reviewed by design-reviewer before implementing. Use for a task without an approved spec that adds behavior across services, a new endpoint or a new event. Bug fixes and changes contained in one service skip it.
---

# Design review before implementing

## 1. Decide whether it applies

It applies when the task has no approved spec in `docs/specs/` and adds
behavior across services, a new endpoint or a new event. Skip it for bug
fixes and changes contained in one service, and say so in the PR.

## 2. Write the design note

Read the ADRs the task touches and the affected code first. Then create a
branch and write `docs/specs/<short-name>.md`:

```markdown
# Design: <title>

Status: design note, reviewed by design-reviewer

## Task
<the issue body or the user's words, copied, not paraphrased>

## Approach
<what changes in which service, in a few paragraphs>

## Alternatives considered
<each with one line on why not>

## Contracts
<changed endpoints, events, schema; "none" if none>

## Failure modes
<Kafka down, duplicates, concurrency, partial failure: how each is handled>

## Test plan
<per behavior: happy and failure paths, and the layer for each>

## Ask first
<any API contract, schema/event, security or dependency change; "none">
```

Keep it to what the implementation needs, about a page. If "Ask first"
isn't "none", stop and ask the user before going on.

Commit the note.

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
- **blocking (trade-off)**: hand back to the user with both options and
  wait for the answer.
- **A hidden "Ask first" change**: ask the user, even if the finding is
  only optional.
- **optional**: fold in the plainly right ones; list the rest in the PR.

After revising, start a fresh `design-reviewer` on the revised note with
the same two inputs. Stop and hand back if a revised finding comes back
confirmed, or if two revisions in a row still have confirmed blocking
findings.

## 5. Implement against the note

The approved note is the spec. When `verify` runs, it gives
`spec-to-diff-reviewer` this note. If the implementation has to depart
from it, update the note in the same PR and say why in the PR Summary.
