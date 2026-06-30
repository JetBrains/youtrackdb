# Research Log — track-reconciliation-rules

## Initial request

> I would like to change rules of maximum of iterations of reviews depending on track risk level: 1. low - iterate till blockers are found. 2. medium - as low + iterate up to 3 times for should-fix 3. high as medium but iterate till there are should fix and blockers without boundaries on interatins count.

## Decision Log

### 2026-06-30T12:56Z [ctx=safe] D1: "Track risk level" = the per-track complexity tag
The new iteration policy is keyed on the per-track complexity tag
(`low`/`medium`/`high`, reconciled to `max(step tags)` and read
track-scoped from the phase ledger at the A→C boundary), not the per-step
risk tag.
- **Why:** The per-track complexity tag is the only tag that drives review
  iteration today (the Phase-C rigor dial). It is available at Phase C.
  The proposal is a refinement of that existing dial.
- **Alternatives rejected:** Per-step risk tag — it is step-scoped and
  gates Phase-B step review, not track-level loops; keying a track loop on
  a per-step tag has no single value to read.

### 2026-06-30T12:56Z [ctx=safe] D2: Scope is Phase-C track-level code review only
The new per-level iteration policy changes the Phase-C track code review
loop's rigor dial only. Phase-2 plan reviews, Phase-A track reviews, and
Phase-B step reviews keep the canonical cap-3-then-escalate behavior of
`review-iteration.md` §Limits.
- **Why:** The per-track complexity tag is only reconciled and available at
  the A→C boundary, so Phase-2 plan reviews run before any track tag
  exists. Phase C is where the dial already lives, so the blast radius is
  smallest and the change is coherent with the existing override pattern.
- **Alternatives rejected:** All review loops — would require inventing a
  tag source for Phase 2 (no track tag yet) and is larger/messier.
  Phase-C + Phase-A — Phase A keys panel breadth off the tag already, but
  the user scoped the iteration-depth change to Phase C only.

**D2.1 (revision 2026-06-30T13:09Z, addresses A4): wire the carve-out into
§Limits, do not merely assert the override in `track-code-review.md`.**
`review-iteration.md` §Limits is the canonical shared home and its TOC
filter is `roles=...,reviewer-dim-track phases=2,3A,3B,3C` — it **loads in
Phase C** (`review-iteration.md:35`). A Phase-C reader who lands on §Limits
(for example via the `review-agent-selection.md` §rigor-dial cross-reference)
would read "Max 3, then escalate," which now contradicts the new uncapped
Phase-C policy. So §Limits **is** in scope after all: it keeps
cap-3-then-escalate as the default for Phases 2/3A/3B and gains an explicit
one-sentence carve-out — "Phase-C track code review overrides this per
`track-code-review.md` §Review loop: iteration depth is keyed to the
per-track complexity tag, with no fixed cap, terminated by no-progress
detection." The override is announced at the canonical home, not silently
asserted only at the override site. This is a one-line edit to §Limits; its
default behavior for non-Phase-C loops is unchanged.

### 2026-06-30T12:56Z [ctx=safe] D3: Per-level iteration policy (the new dial)
The Phase-C review loop terminates per the reconciled complexity tag. Stated
as the **delta from today's behavior** (A5 — `low` already loops on blockers
within the cap-3 ceiling per `track-code-review.md:688-690`; the change is
removing the cap, not introducing the loop):
- `low` → the blocker loop `low` already runs today continues until no
  blockers remain, with the cap **removed** (terminated by no-progress
  detection, D4). Should-fix does **not** drive iteration; remaining
  should-fix surface at track completion. `low` therefore relies entirely on
  no-progress detection for termination — it has no should-fix cap as a
  backstop.
- `medium` → as `low` (uncapped blocker loop) **plus** iterate up to 3 times
  to clear should-fix; after 3, remaining should-fix surface at track
  completion.
- `high` → loop until no blockers **and** no should-fix remain; no fixed
  iteration cap on either, terminated by no-progress detection.
- **Why:** Decouples blocker-looping (always loop until clear) from
  should-fix-looping (depth scales with complexity), matching the user's
  intent that higher-risk tracks get more rigorous convergence.
- **Alternatives rejected:** Keeping the flat cap-3 for all levels — does
  not let `high` converge fully. The current dial's "iterate to convergence
  within the cap-3 ceiling" for `high` — capped at 3, contradicts the
  user's "no bound on iteration count."

**D3.1 (revision 2026-06-30T13:09Z, addresses A3): the `medium`
shared-counter interaction.** Today the iteration counter is **shared across
all dimensions, not independent counters** (`track-code-review.md:834`).
`medium` needs the blocker loop uncapped while the should-fix loop caps at 3
on that one counter. Resolution: keep the single shared counter. Should-fix
findings **stop driving new iterations** once 3 iterations have run; a
surviving blocker **continues** to drive iterations past 3 (bounded by D4's
no-progress detection). A should-fix finding that re-surfaces in a post-3
blocker-driven iteration is fixed opportunistically when the implementer is
already touching that code, otherwise surfaced at track completion. So the
counter stays single — "should-fix drives iteration" is gated on
`iteration ≤ 3`, "blocker drives iteration" is not. No second counter is
introduced.

### 2026-06-30T12:56Z [ctx=safe] D4: No-progress detection replaces the cap-3 escalation safety valve
Because blocker loops (all levels) and `high`'s should-fix loop are now
uncapped, termination uses no-progress detection: escalate to the user when
an iteration makes no progress, rather than capping at a fixed iteration
count.
- **Why:** An unbounded loop on a finding the implementer cannot fix would
  loop forever (across sessions). No-progress detection bounds the loop on
  the real signal (are findings shrinking?) without a fixed cap, so a
  genuinely-converging high-risk track is never cut off early.
- **Alternatives rejected:** Hard ceiling that still escalates — simpler but
  reintroduces an arbitrary cap the user wanted removed. Truly unbounded —
  a stuck blocker loops indefinitely with no escape.

**D4.1 (revision 2026-06-30T13:09Z, addresses A1): operational definition of
no-progress, built on the existing gate-check verdict stream.** No-progress
detection is **not** a new mechanism — it is read off the verdict stream
`review-iteration.md` §Gate-check verdict handling already emits per carried
finding (`VERIFIED` / `REJECTED` / `MOOT` / `STILL OPEN` / `REGRESSION`).
Definition, on all three axes A1 flagged:
- **Identity** — a finding is "the same" by its reviewer-assigned `id` (the
  cumulative finding ID), the unit the gate-check already verdicts by.
- **Threshold** — an iteration makes **no progress** when its gate-check
  returns `STILL OPEN` for every finding carried into it and clears **none**
  (no `VERIFIED` / `MOOT` / `REJECTED`) **and** surfaces no new fixable
  finding. One net clear, or one new fixable finding, is progress. A
  `REGRESSION` is always progress-negative and escalates immediately (it
  already forces a `FAIL` per existing handling).
- **Which loop** — the rule gates each uncapped loop: the blocker loop (all
  levels) and `high`'s should-fix loop. The bounded `medium` should-fix loop
  is bounded by its cap-3, so no-progress detection is moot there until a
  blocker carries it past 3.
On a no-progress iteration the orchestrator escalates to the user (surfaces
the surviving findings + the per-iteration verdict history) rather than
looping again — the same escalation shape the cap-3 exhaustion used, fired on
the no-progress signal instead of a fixed count.

**D4.2 (revision 2026-06-30T13:09Z, addresses A6): composition with the
existing per-iteration context pause.** The Phase-C §Review loop already
carries a mandatory per-iteration context-consumption check
(`track-code-review.md:813-831`) that halts at `warning` (≥40%) /
`critical` (≥50%) and writes a `mid-phase-handoff.md`. The two termination
mechanisms compose on **orthogonal axes**: the context pause bounds
**per-session** burn (unchanged — it pauses and resumes next session); the
cross-session resume re-reads loop state (`track-code-review.md:832`).
No-progress detection bounds **convergence** — it escalates when findings
stop shrinking **across** iterations, including across a resume. So a
slow-but-real-progress `high` track hits the context pause, hands off, and
continues next session (real progress each iteration ⇒ never escalates); a
stuck track escalates on the **first** no-progress iteration regardless of
context level. The context pause never substitutes for no-progress
escalation, and no-progress escalation never preempts a context pause.

## Surprises & Discoveries

### 2026-06-30T12:56Z [ctx=safe] A complexity-keyed rigor dial already exists at Phase C
The per-track complexity tag already moves a "rigor dial" on the Phase-C
track-level code review loop. Defined in `review-agent-selection.md`
§"Complexity sets the Phase-C rigor dial, never the set" and applied in
`track-code-review.md` §Review loop. Current mapping:
- `low` → single shallow pass (run once; do not iterate for should-fix;
  blocker/REGRESSION still forces the loop to continue).
- `medium` → normal cap-3 iteration.
- `high` → iterate to convergence within the cap-3 ceiling (run all 3).
The user's proposal is a refinement of this dial, not a greenfield rule.

### 2026-06-30T12:56Z [ctx=safe] The cap-3 + escalate is the canonical shared protocol
`review-iteration.md` §Limits is the single canonical home: "Max 3
iterations per review type; if blockers persist after 3 iterations,
escalate." Shared by Phase-2 structural/consistency reviews, Phase-A
track pre-execution reviews, Phase-B step dimensional review, and the
Phase-C track code review. `conventions.md` §1.3 points to it. The
cap-3-then-escalate is the safety valve that stops an unfixable blocker
from looping forever.

### 2026-06-30T12:56Z [ctx=safe] Two distinct tag axes; "track risk level" is ambiguous
The workflow has a per-*track* complexity tag (low/medium/high, drives
review rigor, reconciled to max(step tags) at the A→C boundary) and a
per-*step* risk tag (low/medium/high, gates step-level review). "Track
risk level" most naturally maps to the per-track complexity tag (the only
tag that drives review iteration today), but the wording overlaps the
risk-tag vocabulary.

### 2026-06-30T12:56Z [ctx=safe] Prose-only workflow-modifying change
This change edits only workflow prose rules (Markdown under
`.claude/workflow/**` and `.claude/skills/code-review/SKILL.md`) — no
scripts, hooks, settings, or Java code. That makes it a candidate for the
§1.7(k) prose-rule self-application opt-out: edit the workflow prose live
rather than staging it under `staged-workflow/`. The design-gate classifier
at Step 4 owns this call.

### 2026-06-30T12:56Z [ctx=safe] Suggestions never drive iteration at any level
The Phase-C loop's hard continuation/exit gate is blockers (and
`REGRESSION`). should-fix is fixed during iterations and, for `high`,
"iterate to convergence" drives should-fix to clear. Suggestions are
optional: applied opportunistically, routed to plan corrections if
out-of-track, or dropped if rejected — a leftover suggestion never causes
a FAIL or another round. The new dial (D3) correctly stays silent on
suggestions; nothing changes for them.

### 2026-06-30T12:56Z [ctx=safe] (revised 2026-06-30T13:09Z, A2) Full blast radius — cap-3-keyed mechanics uncapping breaks
The change is **not** a 4-file prose touch. Uncapping the `low`/`medium`
blocker loop and the `high` should-fix loop invalidates multiple cap-3-keyed
mechanics inside `track-code-review.md` alone that a derived artifact must
restate, or it ships a self-contradictory file. **The complete set is the
output of `grep -nE '3 iterations|N/3|/3|of 3|three iteration' .claude/workflow/track-code-review.md`
— the author runs that grep and restates every hit; the list below is the
major sites, not exhaustive** (A7; the `three iteration` alternative catches
the spelled-out count at 491/685 the digit-only pattern misses, A8). The full
hit set as of this writing:
lines 491, 527, 685 (the dial site), 724, 765, 832, 837, 848, 875, 1092,
1106, 1256. The major sites and how each is restated under the new policy:

- `track-code-review.md` §Review loop — the dial site (≈681-693) gets the
  new mapping. Plus, all keyed to the cap, in the same section:
  - **Progress format** (≈765): `Track-level code review iteration N
    complete (N/3 iterations)` → drop the `/3` denominator; record
    `iteration N complete` (no fixed denominator).
  - **Step 4** (≈832-847): "Max 3 iterations **total across sessions** — on
    resume, read the iteration count from the Progress section to determine
    how many remain." The cross-session **resume reads the cap** to compute
    remaining iterations; with no cap there is no "remaining" count. Restate:
    resume reads the running iteration count **plus** the no-progress /
    open-findings state, not a remaining-cap count.
  - **Pre-spawn-split rationale** (≈837): "consumes 2 of 3 iterations" — the
    "of 3" framing goes; the split still consumes iterations, just against no
    fixed ceiling.
  - **Step 5** (≈848): "If blockers persist after 3 iterations, ... note the
    unfixed findings" → "if the loop escalates on no-progress with blockers
    open, note the unfixed findings."
  - **Step 6 commit guard** (≈875): "not when the loop exited with blockers
    still open after 3 iterations" → "...exited on no-progress with blockers
    still open."
  - **Checklist seed** (≈1256): `(1/3 iterations, iteration 1...)` template →
    align to the cap-free Progress format.
  - **Cost models** (≈491, ≈527): "reviewers × three iterations = eighteen
    spawns" and "× 3 iterations per track" — these use the cap as a **cost
    bound** the change removes; reword to a representative/typical count, not
    a hard `× 3`.
  - **Failure/budget mentions** (≈724 "2 of 3 used", ≈1092 `FAILED at
    iteration N/3`, ≈1106 "If blockers persist after 3 iterations, note
    them") — restate without the `/3` denominator and against the
    no-progress exit rather than a fixed count.
- `review-agent-selection.md` §"Complexity sets the Phase-C rigor dial,
  never the set" — the dial-mapping prose (low/medium/high) gets the new
  policy; its cross-reference to §Limits stays.
- `code-review/SKILL.md` (≈225) — the standalone-skill note describing the
  dial. The skill takes no complexity input, but its prose ("low single
  shallow pass / medium cap-3 / high iterate to convergence") must stay in
  sync with the new mapping.
- `review-iteration.md` §Limits — **edited** (per D2.1): keeps
  cap-3-then-escalate as the default for Phases 2/3A/3B, gains the one-line
  Phase-C carve-out sentence so a Phase-C reader is routed to the override.

## Open Questions

### 2026-06-30T12:56Z [ctx=safe] RESOLVED — tag axis = per-track complexity tag (D1); scope = Phase-C only (D2); safety valve = no-progress detection (D4)

### 2026-06-30T12:56Z [ctx=safe] Which tag axis and which review loops?
- Does "track risk level" = the per-track complexity tag? (assumed yes)
- Does the new dial apply only to Phase-C track-level code review (where
  the dial lives today), or also to Phase-A track reviews / Phase-2 plan
  reviews / Phase-B step reviews? The per-track complexity tag is only
  reconciled and available at the A→C boundary, so Phase-2 plan reviews
  run before any track tag exists.

### 2026-06-30T12:56Z [ctx=safe] Removing the escalation safety valve
The proposal loops on blockers "till cleared" with no cap (low/medium)
and `high` loops unbounded on both blockers and should-fix. The current
cap-3-then-escalate exists so a blocker the implementer cannot fix
escalates to the user instead of looping forever. An unbounded blocker
loop needs a termination/escalation story (no-progress detection, or a
hard ceiling that still escalates).

## Baseline and re-validation

## Adversarial gate record

### Adversarial review of this log (2026-06-30T13:09Z) — NEEDS REVISION: 2 blockers, 4 should-fix
See `_workflow/reviews/research-log-adversarial-iter1.md`. Blockers: A1
(no-progress detection undefined), A2 (footprint undercounts the cap-3-keyed
blast radius). Should-fix: A3 (medium shared-counter interaction), A4 (§Limits
contradiction unwired), A5 (low delta mis-framed), A6 (context-pause
composition). All addressed by strengthening D2/D3/D4 and the Surprises
footprint below; gate re-run pending.

### Adversarial review of this log (2026-06-30T13:18Z) — PASS
See `_workflow/reviews/research-log-adversarial-iter3.md`. Iter2 closed all 6
iter1 findings (A1–A6 VERIFIED) and raised A7 (should-fix); iter3 VERIFIED A7
closed with A1–A6 still closed. One new finding A8 (suggestion, non-gating:
the cited grep missed the spelled-out "three iteration" count) was fixed in
the footprint anyway. Gate clear: 0 blockers, 0 should-fix.
