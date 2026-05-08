---
severity: medium
phase: phase-b
source-session: 2026-05-08 /execute-tracks unit-test-coverage
---

# Phase B model-selection rule does not consider concurrency / MT-test density within low-risk steps

## Symptom

At Phase B startup of Track 20 (Storage Cache & WAL), the orchestrator
queued six steps all tagged `risk: low` per the existing
`risk-tagging.md` criteria (all test-additive; no production-source
changes). Per `step-implementation.md` § Implementer Prompt Template,
`risk: low` selects `model: "sonnet"`. The orchestrator was about to
spawn the first implementer with sonnet when the user interrupted:

> "Hold on should not we use opus for MT based tests, seems like they
>  are complex enough. WDYT?"

A quick scope review confirmed the user's intuition:
- **Step 1**: pure-POJO WAL-record round-trips, *plus* a 4-producer +
  1-consumer MPSC deque concurrency smoke (`ConcurrentTestHelper`-style,
  CountDownLatch synchronisation, < 5 s).
- **Step 4**: WOWCache lifecycle *plus* three named falsifiable
  WHEN-FIXED-pinned regression probes for production concurrency races
  (`addOnlyWriters`/`removeOnlyWriters` counter-set non-atomicity,
  `fileIdByName` visibility race between `nameIdMap.put`/`idNameMap.put`,
  `store` re-entry silent-swallow). All three probes use
  `Mockito.mock(..., CALLS_REAL_METHODS)` plus reflection-injected
  fields plus `CountDownLatch` synchronisation, no `Thread.sleep()`.
- Steps 2, 3, 5, 6: direct construction in temp directories,
  page-level pattern, verification — no MT design.

Steps 1 and 4 carry concurrent-test design as a load-bearing deliverable.
Sonnet handles structurally-simple test code very well, but
concurrency-test design (latch placement, race-window construction,
absence of `Thread.sleep`, MT smoke under 5 s) is exactly where opus's
extra reasoning depth pays off. The user proposed opus for Steps 1 & 4
and sonnet for Steps 2/3/5/6 — the orchestrator confirmed via
`AskUserQuestion` and used that allocation. Both opus-step implementers
produced clean, canonical RESULT blocks; the sonnet steps produced a
mix (Step 5 emitted a malformed RESULT block — see
`2026-05-08-implementer-malformed-structured-return.md`).

## Reproduction context

- Phase: phase-b
- Workflow doc(s) involved:
  - `.claude/workflow/risk-tagging.md` § "Risk levels — quick reference"
    (the model allocation table)
  - `.claude/workflow/step-implementation.md` § Implementer Prompt
    Template (model selection on spawn)
- Tool / sub-agent involved: `general-purpose` sub-agent (model
  selected per risk tag)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any Phase B step that is tagged `risk: low` (so
  the default model is sonnet) AND has concurrent-test design as a
  load-bearing deliverable — characterised by at least one of:
  - "ConcurrentTestHelper smoke" or equivalent (≥2 producer/consumer
    threads, latch / barrier synchronisation, < 5 s timeout)
  - WHEN-FIXED-pinned regression test for a named production race
  - Reflection-injected field plus mock-with-CALLS_REAL_METHODS plus
    CountDownLatch in the same test class
  - "no `Thread.sleep`" rule explicitly enforced for the test
- Concurrency tests embedded in test-additive steps stay `risk: low`
  per the existing rule (no production-source changes), so the
  current rule cannot signal model elevation.

## Why it's a problem

The user must interrupt Phase B startup to override the model whenever
a low-risk step has dense MT content. This costs the user a manual gate
on every track that has concurrency probes (Track 20 was one; future
tracks targeting WAL / storage / cache concurrency primitives will
likely be similar).

If the user does *not* interrupt, sonnet handles the MT step. It may
work — but concurrency-test design is exactly the area where the
sonnet→opus delta is largest. A sonnet implementer that gets the latch
placement subtly wrong (e.g. signals on the wrong side of the race
window, or loses determinism by relying on scheduling instead of
synchronisation) would ship a flaky regression test that passes most
of the time but does not actually pin the race.

The risk-tagging system was designed to gate `risk: high`'s dimensional
review loop, not to perfectly track model fitness. But model selection
is downstream of the same tag, so any axis the tag misses (MT density,
unusual reflection / IDE-API usage, novel mocking pattern) silently
produces under-modeled spawns.

## Proposed fix

Three options, ordered cheapest to most invasive:

**A. (Cheapest) Add a Phase B startup model-review check.** Edit
`.claude/workflow/step-implementation.md` § Phase B Startup, after the
"Detect orphan commits" sub-step, add:

```
4. Scan steps for model-elevation triggers. For each step in the
   step file, scan the step description for any of:
   - "ConcurrentTestHelper" / "CountDownLatch" / "CyclicBarrier"
   - "WHEN-FIXED-pinned" / "named concurrency shape" / "race"
   - "reflection" + "mock" + ("latch" or "barrier")
   - explicit "≥N producers" / "MT smoke" phrasing
   If any trigger fires AND the step's risk tag is `low` (model
   default = sonnet), present the matched steps to the user with
   "Recommend opus for these steps?" and proceed per their decision.
```

This keeps `risk-tagging.md` unchanged, adds a small startup-time
review, and lets the user gate the override (matches the current
manual flow but eliminates the interruption-mid-launch shape).

**B. Add an MT/concurrency criterion to risk-tagging.** Edit
`.claude/workflow/risk-tagging.md` § Categories to add a "concurrency
test design" trigger that promotes a step from `low` to `medium` (and
hence sonnet → opus) when the step's deliverables include a
concurrency probe of the kind described in the trigger condition
above. This widens the dimensional-review-loop gate too, which may be
desirable but is heavier than option A.

**C. Decouple model selection from risk tag.** Add a separate
`model:` field per step (assigned during Phase A decomposition along
with the risk tag) so the dimensional-review gate (which is
risk-tag-driven) and the model selection (which is
complexity-driven) move independently. Most expensive, requires
Phase A decomposer changes and a new step-file field.

Option A is the lowest-cost fix that addresses the recurring
interruption pattern; B and C are durable but heavier.

## Acceptance criteria

- A Phase B startup on a track containing at least one `risk: low`
  step with concurrent-test design as a deliverable produces a
  proactive prompt asking the user about model elevation, *before*
  the first implementer spawns.
- Subsequent low-risk + MT-dense tracks no longer require the user to
  interrupt mid-launch with "should not we use opus for MT-based
  tests".
- (If option B or C is taken) the next track with three named
  concurrency shapes runs Step-4-equivalent under opus by default,
  without user intervention.
- The change does not alter sonnet selection for the common
  test-additive POJO-only step shape (no false promotions).
