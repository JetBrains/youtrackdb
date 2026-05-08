---
severity: medium
phase: phase-c
source-session: 2026-05-08 /execute-tracks unit-test-coverage
---

# Phase C gate-check sub-agent spawns lack a reusable prompt template

## Symptom

After each `Review fix:` commit in Phase C iteration N, the orchestrator
re-spawns review sub-agents to verify the fixes closed the targeted
findings (the "gate check"). `.claude/workflow/track-code-review.md`
§Review loop says *"Spawn **fresh sub-agents** to verify (gate check) —
only re-run the review dimension(s) that had open findings"* — but
provides no gate-check-specific prompt template. The initial-fan-out
canonical context block (§Sub-agents → "Context passed to all
sub-agents") doesn't fit: that block asks the agent to *find* findings
de novo; the gate check asks the agent to *verify a specific subset is
closed and surface any new gaps the fixes introduced*.

In Track 20's Phase C, the orchestrator hand-composed ~40 lines per
gate-check sub-agent across 9 spawns total (5 dimensions × 2 iter +
overlap). Each prompt re-invented:

- the framing ("This is a gate-check of iteration N's `Review fix:`
  commit COMMIT_SHA…");
- the table of "what was fixed" with the iter-N finding ID → fix
  mapping;
- the list of "remaining deferred findings — do not re-flag";
- the gate-check-specific questions ("does the fix actually falsify
  the regression class it claims to catch? did the fix introduce any
  new gaps?");
- the output-format directive (`PASS` / `Findings:` with `*-iter3-N:`
  IDs that don't collide with original IDs).

## Reproduction context

- Phase: phase-c
- Workflow doc(s) involved: `.claude/workflow/track-code-review.md`
  §Review loop (the "Spawn fresh sub-agents to verify" bullet) and
  §Sub-agents (the canonical context block — currently the only
  templated prompt).
- Tool / sub-agent involved: every gate-check spawn in Phase C
  (typically 4–9 per iteration, 1–3 iterations per track).
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: end of any Phase C iteration where the
  per-iteration implementer returned `SUCCESS` and the orchestrator
  needs to re-verify the dimensions.

## Why it's a problem

Hand-composing gate-check prompts on the spot has three downsides:

1. **Tool-call token waste.** Every gate-check spawn embeds ~40 lines
   of bespoke prompt in the orchestrator's tool-call history. With
   typical 5 gate-check spawns × 3 iterations per track × N tracks,
   this accumulates faster than necessary.
2. **Inconsistent shapes.** Different gate-check spawns within the
   same iteration end up with subtly different framings — some have
   the deferred-list, some don't; some have the output-format
   directive, some omit it. This makes gate-check outputs harder to
   triage and dedupe.
3. **Recurring re-invention.** The orchestrator solves the same
   "what should a gate-check prompt look like?" problem each Phase C,
   each iteration. There is no canonical answer, so two agents on the
   same iteration could legitimately produce different gate-check
   shapes.

## Proposed fix

Add a §Gate-check sub-agents subsection to
`.claude/workflow/track-code-review.md` (or as a separate companion
`.claude/workflow/gate-check-template.md` referenced from §Review
loop) with a templated prompt:

```
Review the following code changes from your specialized perspective.

## Workflow Context
This is the **gate-check for Track {N} Phase C iteration {iter_n}**
(`Review fix:` commit `{fix_sha}`). You previously identified
findings {your_dim_finding_ids}; the iter-{iter_n} implementer
applied {applied_finding_ids} (mapped to {applied_fix_ids}). Verify
those are sound and surface any new gaps the fixes may have
introduced. The remaining {your_dim_finding_ids} (deferred to
{deferred_bucket}) are out of scope for this gate check.

## Review Target / Plan / Steps
{same paths as initial fan-out — no copying}

## Iter-{iter_n} fix summary (your dimension)

| Finding ID | Location | Fix applied |
|---|---|---|
{table populated by orchestrator}

## Gate-check questions
{a small list of 3–6 dimension-specific questions, e.g., for
test-behavior: "Did fix X actually falsify the regression class it
claims to catch?"; for bugs-concurrency: "Did the fix introduce any
new races / leaks?"}

## Diff to review
`git diff {fix_sha}~1..{fix_sha} -- 'core/**'`

Output format: **PASS** if {your_dim_finding_ids_addressed} are
closed, OR a `Findings:` list with `{your_dim_id_prefix}-iter{iter_n+1}-N:`
IDs. Concise — under 600 words.
```

The orchestrator fills in `{N}`, `{iter_n}`, `{fix_sha}`,
`{applied_finding_ids}`, `{applied_fix_ids}`, the per-row table, and
the gate-check question list (which itself can have a per-dimension
default per `.claude/workflow/review-agent-selection.md`).

## Acceptance criteria

- A canonical gate-check spawn template exists in
  `.claude/workflow/track-code-review.md` (or a referenced companion).
- The template's variable section is short enough that the
  orchestrator's per-spawn cost drops to ~10 lines from ~40.
- A worked example shows the template populated with a realistic
  finding-table and dimension-specific questions.
- Per-dimension default question lists are listed (test-behavior,
  bugs-concurrency, test-completeness, test-concurrency, crash-safety,
  performance, code-quality, test-structure, security) so the
  orchestrator does not invent them per spawn.
- Regression check: future Phase C sessions producing gate-check
  spawns in similar tracks (e.g., Track 21, Track 22) reach the
  next-iteration spawn faster than Track 20's session did, with the
  same gate-check coverage and shape.
