---
severity: medium
phase: phase-b
source-session: 2026-05-08 /execute-tracks unit-test-coverage
---

# Sonnet implementer returns narrative without structured RESULT block under wide-scope steps

## Symptom

During Phase B Step 4 of Track 19 (Storage Fundamentals — collections + ridbag,
~543 uncov lines, two test patterns, 11 files / 250 tests / 1,098 LOC), the
sonnet-class implementer sub-agent returned a free-form bullet-list
narrative naming the new test classes, the commit SHA, and concluding
"Coverage gate: n/a (test-additive step — no production source changes)" —
but **omitted the entire `RESULT: SUCCESS / FILES_TOUCHED / TEST_SUMMARY /
TOOLING_NOTES / EPISODE_DRAFT / CROSS_TRACK_HINTS` block** that
`implementer-rules.md` § Return contract makes mandatory. The orchestrator
recovered by reading the commit + diff directly, but had to author the
EPISODE_DRAFT itself rather than receiving one from the implementer.

The earlier Step 2 and Step 3 spawns (also sonnet, also Track 19, similar
test-additive scope but smaller — 78 tests / 1,158 LOC and 35 tests / 609 LOC
respectively) both produced clean, complete RESULT blocks. The recurrence
correlates with **scope width**: Step 4 touched 11 distinct test classes
across four packages, while Steps 2–3 touched 3 classes each in a single
package.

## Reproduction context

- Phase: phase-b
- Workflow doc(s) involved: `.claude/workflow/implementer-rules.md` § Return
  contract; `.claude/workflow/step-implementation.md` § Implementer Prompt
  Template
- Tool / sub-agent involved: `general-purpose` sub-agent with
  `model: "sonnet"` (low-risk step model selection)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any Phase B step with `risk: low` (sonnet model) and
  wide scope — multiple test classes across multiple packages with high
  test count per spawn. The orchestrator's prompt does include the
  "Mandatory RESULT block on every exit" clause, so the rulebook reference
  alone is not enough preventive friction.

## Why it's a problem

When the orchestrator has to reconstruct the episode from git instead of
receiving a structured EPISODE_DRAFT, it loses the implementer's first-hand
account of:

- *What was discovered* — observations the implementer made while authoring
  (e.g., the `EdgeKeySerializer` empty-buffer AIOOBE, the constructor-signature
  fixes that were needed for `RidbagBucketEntryBulkOpsTest`).
- *What changed from the plan* — micro-deviations that fitted the actual
  test-class shape vs. the plan's prescription.
- *Cross-track hints* — observations the implementer is well-positioned to
  flag but the orchestrator can only guess at from the diff.

In Track 19 Step 4 the orchestrator did capture three discoveries from the
narrative (stray artifact, AIOOBE, contract-violation note), but only because
the narrative happened to mention them in passing. A more terse narrative
would have left those signals undetected, and they would never have reached
Track 22's deferred-cleanup queue.

This is also a contract-violation pattern that Step 5's spawn prompt only
worked around by adding an explicit cautionary preamble. That fix isn't
durable — the next Track will start fresh and not know to add it.

## Proposed fix

Multiple options; (b) and (c) are the most likely candidates and could
combine:

(a) **Document the wide-scope failure mode in `implementer-rules.md`** so
the implementer is reminded the structured block matters even when the
narrative feels self-explanatory. Add a § "Return contract — common
failure modes" subsection naming the wide-scope-narrative pattern
specifically.

(b) **Auto-upgrade `risk: low` steps to `model: "opus"` when scope width
exceeds a threshold** (e.g., FILES_TOUCHED ≥ 5 or test count ≥ 100 in the
implementer's preview). The rulebook's risk-tagging rules cover invasive
implementations but not test-additive scope-width. The decomposer (Phase A)
could annotate `expected_files: N` and the orchestrator could choose model
accordingly. This is a structural fix — the implementer's response quality
becomes a function of the model rather than depending on prompt
discipline.

(c) **Make the orchestrator's spawn prompt require the structured block
preamble unconditionally**, not just when a prior implementer in the
session violated it. The Step 5 spawn this session worked around the
problem ad-hoc, but the warning should be baked into the canonical
template at `step-implementation.md` § Implementer Prompt Template, not
added on demand.

(d) **Reject narrative-only returns at orchestrator parse time and
re-spawn with `mode=WITH_GUIDANCE` carrying "your prior return omitted
the RESULT block — re-emit it"**. The current orchestrator silently
recovers from git, which removes the feedback signal that would teach
the implementer to fix the contract.

## Acceptance criteria

- A clear rule lives in either `implementer-rules.md` § Return contract or
  `step-implementation.md` § Implementer Prompt Template that addresses the
  wide-scope-narrative failure mode by name.
- A regression check: a Phase B step with FILES_TOUCHED ≥ 10 produces a
  return that contains the literal `RESULT: SUCCESS` (or the appropriate
  non-SUCCESS variant) prefix.
- If option (b) is chosen: the decomposer's step-file output includes a
  scope-width hint and the orchestrator's model selection consults it.
- If option (d) is chosen: a parser in the orchestrator detects "no
  RESULT block" and re-spawns with `WITH_GUIDANCE`; the existing handler
  table in `step-implementation.md` § Per-Step Orchestration Loop names
  the new branch.
