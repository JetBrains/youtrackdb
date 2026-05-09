---
severity: medium
phase: phase-a
source-session: 2026-05-09 /execute-tracks unit-test-coverage
---

# Phase A has no protocol for a context-warning checkpoint that hits between iter-1 reviewer responses and the orchestrator's iter-2 decision

## Symptom

During Track 22's Phase A iteration 1, the orchestrator spawned three
review sub-agents (Technical, Risk, Adversarial) in parallel. All
three returned findings within the same session. Total findings: 27
across 1 BLOCKER (A3 — track scope), 13 should-fix, 13 suggestions.
Several findings overlapped across reviewers (R5 + A3 + R9 on track
size; R3 + A6 on production-fix policy; T1 + A1 on SPI rule wording).
Two findings (A3 BLOCKER, A4 should-fix on Goal feasibility) touched
deep-amendment categories and required user decision.

The orchestrator consolidated findings, surfaced A3 + A4 to the user,
and captured the user's decisions (ESCALATE → split into 22a/22b/22c;
amend Goal to ~82–83% / ~70–71%). Up to this point the protocol was
followed.

The friction surfaced at the *handoff* to iteration 2: the
orchestrator's context level had reached **30% (warning)** during
finding consolidation. Per `workflow.md` § Context Consumption Check,
the agent **must not start the next unit of work** at warning level;
must save state and ask for a session refresh. But:

- Iteration 2 of the review loop is the next unit of work.
- `track-review.md` § Track-scoped reviews step 3 says the findings
  "ride in the orchestrator's conversation context for the iteration
  loop, and the durable trace is the resulting step-file edits". I.e.,
  findings are explicitly **not persisted between sessions**.
- A natural read of the protocol: "discard iter-1 findings; the next
  session re-runs each review type from iteration 1 with empty
  `previous_findings`."
- But for Track 22, this would discard the user's deep-amendment
  decisions (ESCALATE / Goal amendment) which arose from the iter-1
  findings — a strict no-persistence read produces the loss-of-context
  failure the workflow's session-refresh protocol is meant to prevent.

The orchestrator had to invent a workaround: write an iter-1 summary
into the step file's `## Reviews completed` section as `[ ]` entries
with prose summarising each reviewer's headline findings, plus an
ad-hoc `## Iteration 1 deferred resolution` section capturing the
user's decisions for the next session's inline-replanning agent to
read. None of this format is prescribed; the orchestrator made
judgement calls about what to preserve.

## Reproduction context

- Phase: phase-a (specifically the iter-1 → iter-2 handoff in the
  review loop)
- Workflow doc(s) involved:
  - `.claude/workflow/track-review.md` § Track-scoped reviews step 3
    (the no-persistence rule for findings)
  - `.claude/workflow/track-review.md` § Phase A Resume — Description-
    move recovery (the resume table that gates on `## Reviews
    completed` checkboxes, not on iter-1 prose)
  - `.claude/workflow/workflow.md` § Context Consumption Check (the
    warning rule)
  - `.claude/workflow/inline-replanning.md` (where the next session
    routes when ESCALATE is captured)
- Tool / sub-agent involved: Phase A review sub-agents (3 in parallel
  via the `Agent` tool)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: Any Phase A session where (a) iteration 1
  reviewers complete normally, (b) findings include a `blocker` or a
  deep-amendment `should-fix` requiring user decision, **and**
  (c) the orchestrator's context-warning checkpoint fires during
  finding consolidation. Likelihood scales with track size — small
  tracks rarely trigger; large tracks (final-sweep, refactor-heavy)
  almost always do.

## Why it's a problem

Three failure modes:

1. **Loss of user-decision context.** A strict reading of the
   no-persistence rule loses the ESCALATE decision the user made
   based on iter-1 findings. The next session re-runs iter-1 reviews,
   produces the same BLOCKER, re-asks the user, and burns another
   sub-agent fan-out (~25 minutes + token spend) to re-derive the
   same outcome.
2. **Ambiguous resume state.** `track-review.md` § Phase A Resume
   gates on the `## Reviews completed` checkboxes. With iter-1 prose
   recorded under each checkbox but the checkboxes still `[ ]`, the
   resume table's "Reviews not yet run" branch fires — but the
   reviews *did* run, just didn't reach gate verification. The protocol
   doesn't distinguish "not run" from "ran but iteration didn't
   complete".
3. **Per-session orchestrator format drift.** Each agent that hits
   this case invents its own format for preserving iter-1 context.
   This session wrote prose under each `[ ]` checkbox plus an ad-hoc
   `## Iteration 1 deferred resolution` section. The next session
   may struggle to parse it, and a future session hitting the same
   case may write a different format.

This boundary is statistically common for large tracks. Track 22 is
extreme but not unique — Track 13 hit the iteration-3 ceiling without
context warning; Track 21 ran 3 implementer iterations across multiple
sessions. Any Phase A run on a Complex track with active reviewers
risks the same boundary if context grows during consolidation.

## Proposed fix

Add an explicit **mid-iteration context-warning protocol** to
`track-review.md`. Two complementary mechanisms:

### 1. Persistence allowance for iter-N summaries when context-warning fires

Relax the no-persistence rule for the specific case of "context-
warning checkpoint between reviewer return and orchestrator decision".
Define a structured iter-N summary format (e.g.,
`## Phase A iteration N summary` section in the step file) that:
- Lists each reviewer's headline findings by ID and severity.
- Captures any user decisions made on iter-N findings (ESCALATE
  acceptance, override rationale, accepted/rejected disposition per
  finding).
- Has an explicit "next-session action" line — either "re-run iter-N
  with the recorded findings as `previous_findings`" or "ESCALATE to
  inline-replanning per recorded user decision".

The next session's resume logic reads this section and skips re-running
the recorded iteration if a follow-up action is named.

### 2. Tighten the Phase A Resume table

Add a row to the resume table at the bottom of `track-review.md` for
the case "step file `## Description` populated; some `## Reviews
completed` checkboxes carry iter-N prose without `[x]` gate
verification". Resume action: read the iter-N summary section and
either re-run iter-N (default) or route to inline-replanning (if a
recorded user decision says ESCALATE).

### 3. Explicit context-warning escape hatch in the review loop

Add a step in `track-review.md` § Track-scoped reviews: "After each
review type's iteration, before starting the next iteration, run the
context check (`cat /tmp/claude-code-context-usage-$PPID.txt`). If
warning, write the iter-N summary section per format above, then end
the session per the warning protocol." This makes the path explicit
rather than requiring the orchestrator to invent it.

## Acceptance criteria

- `track-review.md` § Track-scoped reviews documents the iter-N
  summary persistence format and when to write it.
- `track-review.md` § Phase A Resume includes a resume row for "iter-N
  prose recorded but checkbox `[ ]`".
- A new session entering Phase A on a track that has an iter-N
  summary in its step file reads the recorded findings and either
  resumes iter-N with `previous_findings` populated, or routes to
  inline-replanning if a user decision is recorded.
- Regression check: replaying Track 22's iter-1 → iter-2 handoff
  against the new protocol should produce a clean session boundary
  with no orchestrator-invented format and no ambiguity at resume.
