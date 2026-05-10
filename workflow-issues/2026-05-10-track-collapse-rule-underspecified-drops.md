---
severity: low
phase: phase-c
source-session: 2026-05-10 /execute-tracks unit-test-coverage
---

# Phase C completed-track collapse rule under-specifies the "always drop" set

## Symptom

`.claude/workflow/track-code-review.md` § Track Completion step 4
prescribes the collapse rule for completed-track plan-file entries:

> **Always keep** (regardless of plan shape): the **intro paragraph**
> ..., the `**Track episode:**` block ..., the `**Step file:**`
> pointer, and the `**Strategy refresh:**` line if present ...
>
> **Always drop**: the `**Scope:**` line and the `**Depends on:**`
> line.

Track 22a's plan-file entry carried four post-intro subsections at
collapse time:

1. `**Scope:**` line (always-drop per rule — clear)
2. `**Depends on:**` line (always-drop per rule — clear)
3. `**Operational note:**` block (3 lines pointing at the backlog's
   "Inherited absorption queue" for recovery context)
4. (Eventually) `**Track episode:**` and `**Step file:**` (always-keep)

The `**Operational note:**` is not in the keep list and not in the
drop list — the rule does not cover it. I dropped it on the principle
that the always-keep list is the safer side of "if not specified,
keep it minimal." The implementation-backlog still carries the
underlying recovery context, so dropping the note loses no
information; if I had kept it, the entry would have stayed bloated.

But the rule was silent about the case, and a different orchestrator
could reasonably keep ad-hoc subsections.

## Reproduction context

- Phase: phase-c
- Workflow doc(s) involved:
  - `.claude/workflow/track-code-review.md` § Track Completion
    step 4 ("Always keep" / "Always drop" specification)
- Tool / sub-agent involved (if any): orchestrator (main agent) at
  the track-completion stage
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: Any Phase C track-completion collapse where the
  plan-file entry carries a subsection that is neither in the
  always-keep list nor in the always-drop list. Most common shapes:
  `**Operational note:**`, `**Caveat:**`, ad-hoc clarification
  blockquote paragraphs added during plan review or inline
  replanning.

## Why it's a problem

Two impacts:

1. **Inconsistent collapse output across sessions.** If one orchestrator
   keeps `**Operational note:**` and another drops it, completed-track
   entries diverge in shape. Future sub-agents reading the slim plan
   snapshot see different levels of historical context per track,
   making "is this track's full context still in scope?" judgment
   harder.
2. **Collapse-rule ambiguity invites scope creep.** Without an explicit
   "drop everything else" rule, an orchestrator might keep ad-hoc
   subsections to play it safe — the very bloat the collapse step
   exists to prevent.

Severity is `low` because the orchestrator's natural reading
(always-keep is a closed list, drop the rest) produced the right
outcome here. But making the rule explicit prevents the next
orchestrator from playing it differently.

## Proposed fix

Edit `.claude/workflow/track-code-review.md` § Track Completion
step 4 to make the always-drop set closed:

```
**Always keep** (regardless of plan shape): the **intro paragraph**
(the first paragraph of the original description, before any
`**What**:` / `**How**:` / `**Constraints**:` / `**Interactions**:`
subsection), the `**Track episode:**` block (written at collapse
time), the `**Step file:**` pointer, and the `**Strategy refresh:**`
line if present — though that line is never yet on disk at Phase C
collapse time; the next session's Track Pre-Flight gate appends it
when Panel 1 (strategy assessment) clears (see
[`track-review.md`](track-review.md) § Track Pre-Flight step 6).

**Always drop**: every subsection of the original description that
is not in the always-keep list above. Specifically: the `**Scope:**`
line, the `**Depends on:**` line, and any ad-hoc subsections (e.g.,
`**Operational note:**`, `**Caveat:**`, `**Inheritance:**`, free
blockquote paragraphs added during plan review or inline replanning).
The intent: completed tracks shrink to the minimum forward-strategic
form, and any historical implementation detail belongs in the step
file or the backlog rather than the plan-file entry.
```

This is a one-paragraph addition; the existing always-keep
specification stays unchanged.

## Acceptance criteria

- `.claude/workflow/track-code-review.md` § Track Completion step 4
  carries an explicit "every subsection not in the keep list" drop
  clause.
- A future Phase C track-completion that touches a track carrying an
  `**Operational note:**` or similar ad-hoc subsection drops it
  without orchestrator deliberation.
- The shape of completed-track entries is consistent across the plan
  file (intro paragraph + Track episode + Step file pointer +
  optional Strategy refresh, nothing else).
