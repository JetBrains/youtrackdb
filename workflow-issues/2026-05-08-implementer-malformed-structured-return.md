---
severity: medium
phase: phase-b
source-session: 2026-05-08 /execute-tracks unit-test-coverage
---

# Sonnet implementer returns near-canonical-looking but malformed RESULT block

## Symptom

During Phase B Step 5 of Track 20 (Storage Cache & WAL — CHM read cache +
small CHM packages, 5 new test classes / 35 tests / ~1 363 LoC, sonnet
model selected by `risk: low`), the implementer emitted a return block
that *looked* canonical at first glance but did not parse cleanly:

```
RESULT
step_index: 5
status: success
commit: ceed36af93
tests_added: 35
coverage_run: n/a (test-additive)

Files created:
- core/.../BoundedBufferRingTest.java — 7 tests: …
- core/.../BoundedBufferDrainTest.java — 4 tests: …
…
```

Compare with the canonical contract from
`implementer-rules.md` § Return contract:

```
RESULT: SUCCESS
COMMIT: <sha>
FILES_TOUCHED:
- <path> (new|modified)
TEST_SUMMARY:
  module: …
  passed: …
  …
TOOLING_NOTES:
  …
EPISODE_DRAFT:
  what_was_done: …
  …
CROSS_TRACK_HINTS: …
```

Differences that broke parsing:
1. `RESULT` on its own line, not `RESULT: SUCCESS`.
2. Snake-case ad-hoc fields (`step_index`, `status`, `tests_added`,
   `coverage_run`) replacing the canonical `COMMIT:`, `FILES_TOUCHED:`,
   `TEST_SUMMARY:` block hierarchy.
3. `FILES_TOUCHED:` replaced with prose "Files created:" and
   `(new|modified)` annotations replaced with "— N tests" descriptions.
4. `TEST_SUMMARY` collapsed to `tests_added: 35`; no `module` /
   `passed: <N> / <N>` / `spotless_applied`.
5. `EPISODE_DRAFT` block omitted entirely — no `what_was_done`,
   `what_was_discovered`, `what_changed_from_plan`, `critical_context`.
6. `TOOLING_NOTES` block omitted.
7. `CROSS_TRACK_HINTS` block omitted.

The orchestrator recovered: it ran `git log {step_base_commit}..HEAD`
and `git diff --stat` to verify the commit, re-ran all 35 tests via
Bash to confirm pass status, and authored the entire `EPISODE_DRAFT`
itself from the prose summary plus the diff. ~6 extra orchestrator
turns were spent on recovery work that a canonical RESULT block would
have absorbed in zero turns.

This is a distinct manifestation from
`2026-05-08-implementer-narrative-only-return.md`, which describes a
fully-prose return with no `RESULT` token at all. Here the implementer
*did* emit a `RESULT` token but invented its own field hierarchy. The
two issues share a root cause — sonnet implementers under wide-scope
test-additive steps drifting away from the structured contract — but
the failure modes differ: pure narrative is easy to detect (no `RESULT:`
match), structured-but-non-canonical is harder because the orchestrator
has to spot per-field divergences.

## Reproduction context

- Phase: phase-b
- Workflow doc(s) involved:
  - `.claude/workflow/implementer-rules.md` § Return contract
  - `.claude/workflow/step-implementation.md` § Implementer Prompt
    Template
- Tool / sub-agent involved: `general-purpose` sub-agent with
  `model: "sonnet"` (low-risk step model selection)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any Phase B step where `risk: low` (sonnet model)
  AND scope is wide (multiple test classes across multiple packages,
  >1 000 LoC of new tests). Step 5 of Track 20 had 5 classes in 3
  packages, 1 363 LoC, 35 tests — same wide-scope shape that triggered
  the narrative-only failure mode in Track 19 Step 4.

## Why it's a problem

The orchestrator must spot that the structured-looking block is not
actually canonical, then absorb 6+ extra recovery turns to reconstruct
`EPISODE_DRAFT`, `FILES_TOUCHED`, `TEST_SUMMARY` from the diff and
prose. On a long Phase B session this drift adds up, and the recovery
work re-introduces orchestrator-side context (Maven test re-runs, diff
reads, episode authoring) that the implementer split was specifically
designed to keep out.

The orchestrator-side recovery worked, but only because the work was
test-additive and easy to verify by re-running tests. A
non-test-additive step where the same drift occurred could ship with
incorrect `EPISODE_DRAFT.what_was_discovered` or missing
`CROSS_TRACK_HINTS` because the orchestrator has no fallback signal to
extract them from.

## Proposed fix

Two complementary tightenings:

**A. Add a final-clause reminder to the implementer prompt template.**
Edit `.claude/workflow/step-implementation.md` § Implementer Prompt
Template by appending a short closing block to the prompt — after the
"Mandatory RESULT block on every exit" clause, restate the exact
header sequence the rulebook requires:

```
Your final output's last block MUST start with the literal line
`RESULT: SUCCESS` (or one of `DESIGN_DECISION_NEEDED`,
`RISK_UPGRADE_REQUESTED`, `FAILED`) and follow the field hierarchy
in the rulebook §Return contract verbatim:

  RESULT: <tag>
  COMMIT: …
  FILES_TOUCHED:
    - …
  TEST_SUMMARY:
    …
  TOOLING_NOTES:
    …
  EPISODE_DRAFT:    # at level=step
    what_was_done: …
    what_was_discovered: …
    what_changed_from_plan: …
    critical_context: …
  CROSS_TRACK_HINTS: …

Do not invent ad-hoc fields, do not collapse TEST_SUMMARY into a
single line, and do not omit EPISODE_DRAFT. Re-read the rulebook
§Return contract before exiting.
```

**B. Orchestrator-side strict parser with friendly error.** Add a
parser pass in the orchestrator (Phase B sub-step 4 dispatch in
`step-implementation.md`) that validates the block against the
canonical schema and, on failure, logs a tight diff
("missing field `TEST_SUMMARY.passed`", "expected `RESULT:` not
`RESULT`") then routes through a recovery handler — currently the
orchestrator falls back implicitly. A formalised recovery would let
future agents reproduce the response.

Option A alone is the cheap mitigation; option B is the durable fix
if the friction recurs after A lands.

## Acceptance criteria

- A Phase B implementer spawn under sonnet on a wide-scope test-additive
  step (≥1 000 LoC of new tests, ≥3 test classes in ≥2 packages)
  emits a RESULT block whose first line matches `^RESULT: (SUCCESS|
  DESIGN_DECISION_NEEDED|RISK_UPGRADE_REQUESTED|FAILED)$`.
- The block contains all required field labels: `COMMIT:`,
  `FILES_TOUCHED:`, `TEST_SUMMARY:` (with `module`, `passed`,
  `line_coverage_changed`, `branch_coverage_changed`,
  `spotless_applied`), `TOOLING_NOTES:`, `EPISODE_DRAFT:` (with
  `what_was_done`, `what_was_discovered`, `what_changed_from_plan`,
  `critical_context`), `CROSS_TRACK_HINTS:`.
- The orchestrator's recovery turns (parsing, diff reads, manual
  EPISODE_DRAFT authoring) drop to zero on the next sonnet wide-scope
  step.
- If option B is implemented: a malformed block triggers a single
  formal recovery message ("RESULT block parse failed at field X") and
  re-spawns or fails cleanly, rather than silent orchestrator
  reconstruction.
